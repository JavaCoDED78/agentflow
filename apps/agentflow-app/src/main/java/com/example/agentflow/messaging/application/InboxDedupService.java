package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.InboxMessage;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Inbox idempotency helper (S06-10): the application-facing boundary a consumer uses to apply a
 * business state change for a claimed event <b>atomically</b> with the inbox dedup claim.
 *
 * <p>{@link #consume} runs, in a <b>single</b> PostgreSQL transaction, three steps:
 *
 * <ol>
 *   <li><b>claim</b> &mdash; insert the {@code (consumer_name, event_id)} row as {@code PENDING}.
 *       The composite {@code PRIMARY KEY} makes a second insert of the same pair fail the unique
 *       constraint, so a concurrent duplicate Kafka delivery (at-least-once, ADR-003) is skipped
 *       and never runs the business change &mdash; only the first claim proceeds (the S06-10
 *       acceptance criterion: concurrent duplicate delivery creates exactly one domain transition).
 *   <li><b>execute</b> &mdash; run the {@link InboxConsumerAction} business state change in the
 *       SAME transaction (invariant 3: the event id and the business change commit or roll back
 *       together).
 *   <li><b>mark</b> &mdash; on success move the row to {@code PROCESSED} (fenced to {@code
 *       PENDING}).
 * </ol>
 *
 * <p>Every {@link InboxConsumerAction#execute} call is guarded by a bounded local retry within the
 * same claim: a transient failure's partial changes are rolled back to a single savepoint while the
 * claim row is kept, and the change is retried up to {@code maxAttempts} times. A failure is first
 * classified by {@link InboxErrorPolicy} (S06-11): a <b>permanent</b> / malformed event ({@link
 * InboxErrorClassification#PERMANENT}) <b>short-circuits</b> the bounded immediate retry &mdash;
 * the row is recorded {@code FAILED} and returned as {@link InboxConsumeResult#DEAD_LETTER}, never
 * retried, so a malformed event cannot cycle the consumer forever. A <b>transient</b> failure
 * ({@link InboxErrorClassification#TRANSIENT}) runs the bounded immediate retry; when it is
 * exhausted the row is recorded {@code FAILED} and returned as {@link InboxConsumeResult#RETRY}.
 * Nothing is ever marked {@code PROCESSED} on a failure (the invariant: a transient failure is
 * never marked processed). The {@code RETRY} / {@code DEAD_LETTER} dispositions are held for the
 * consumer retry / DLQ policy (S06-11), which records them and acknowledges the Kafka offset only
 * after a durable {@link InboxConsumeResult#PROCESSED} / {@link InboxConsumeResult#OTHER_CLAIMED}
 * outcome.
 *
 * <p>The business change is applied through the owning module's repository/service (for example a
 * {@code workflowexecution} run/step transition); this helper only owns the inbox claim and its
 * lifecycle, and never runs arbitrary or model-generated code (invariant 7). Model output, uploaded
 * documents and tool results handled by the business change are untrusted data.
 *
 * <p>The bean is only usable when a {@link
 * org.springframework.jdbc.datasource.DataSourceTransactionManager} {@code TransactionTemplate} and
 * an {@link com.example.agentflow.messaging.persistence.InboxMessageRepository} are present (i.e. a
 * database is configured).
 */
public class InboxDedupService {

  private final com.example.agentflow.messaging.persistence.InboxMessageRepository inbox;
  private final TransactionTemplate transactionTemplate;
  private final InboxErrorPolicy errorPolicy;

  /**
   * Default maximum local attempts when a business change fails transiently before the bounded
   * retry is configured.
   */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  private final int maxAttempts;

  /**
   * @param inbox the inbox write/read path
   * @param transactionTemplate the transactional boundary (a {@code DataSourceTransactionManager}
   *     template); the claim, the business change and the mark commit or roll back together
   * @param maxAttempts the maximum local attempts on a transient failure (at least 1)
   */
  public InboxDedupService(
      com.example.agentflow.messaging.persistence.InboxMessageRepository inbox,
      TransactionTemplate transactionTemplate,
      int maxAttempts) {
    this(inbox, transactionTemplate, maxAttempts, InboxErrorPolicy.defaults());
  }

  /**
   * @param inbox the inbox write/read path
   * @param transactionTemplate the transactional boundary (a {@code DataSourceTransactionManager}
   *     template); the claim, the business change and the mark commit or roll back together
   * @param maxAttempts the maximum local attempts on a transient failure (at least 1)
   * @param errorPolicy the transient/permanent classifier (S06-11); a {@code null} policy defaults
   *     to {@link InboxErrorPolicy#defaults()}
   */
  public InboxDedupService(
      com.example.agentflow.messaging.persistence.InboxMessageRepository inbox,
      TransactionTemplate transactionTemplate,
      int maxAttempts,
      InboxErrorPolicy errorPolicy) {
    Assert.state(maxAttempts >= 1, "maxAttempts must be at least 1");
    this.inbox = inbox;
    this.transactionTemplate = transactionTemplate;
    this.maxAttempts = maxAttempts;
    this.errorPolicy = errorPolicy != null ? errorPolicy : InboxErrorPolicy.defaults();
  }

  /**
   * Apply the business state change for the claimed event, retrying a transient failure up to the
   * configured maximum (see the class javadoc).
   *
   * @param consumerName the inbox consumer identity (the "consumer" half of the dedup key)
   * @param claim the fresh {@link InboxMessage.Status#PENDING} claim to insert before the change
   * @param action the business state change to run atomically with the claim
   * @return {@link InboxConsumeResult#OTHER_CLAIMED} (a duplicate delivery skipped the change),
   *     {@link InboxConsumeResult#PROCESSED} (the change ran once) or, once a business change has
   *     failed, {@link InboxConsumeResult#RETRY} (a transient fault exhausted the bounded retry) or
   *     {@link InboxConsumeResult#DEAD_LETTER} (a permanent / malformed event short-circuited the
   *     retry) &mdash; never {@code PROCESSED} on a failure
   */
  public InboxConsumeResult consume(
      String consumerName, InboxMessage claim, InboxConsumerAction action) {
    return consume(consumerName, claim, action, maxAttempts);
  }

  /**
   * Apply the business state change for the claimed event, retrying a transient failure up to
   * {@code maxAttempts} (see the class javadoc).
   *
   * @param consumerName the inbox consumer identity (the "consumer" half of the dedup key)
   * @param claim the fresh {@link InboxMessage.Status#PENDING} claim to insert before the change
   * @param action the business state change to run atomically with the claim
   * @param maxAttempts the maximum local attempts on a transient failure (at least 1)
   * @return {@link InboxConsumeResult#OTHER_CLAIMED}, {@link InboxConsumeResult#PROCESSED}, {@link
   *     InboxConsumeResult#RETRY} (transient fault, bounded retry exhausted) or {@link
   *     InboxConsumeResult#DEAD_LETTER} (permanent / malformed event, retry short-circuited)
   */
  public InboxConsumeResult consume(
      String consumerName, InboxMessage claim, InboxConsumerAction action, int maxAttempts) {
    Assert.notNull(inbox, "inbox dedup repository must not be null");
    Assert.hasText(consumerName, "consumerName must not be blank");
    Assert.notNull(claim, "claim must not be null");
    Assert.notNull(claim.eventId(), "claim.eventId must not be null");
    Assert.notNull(action, "action must not be null");

    // A single transaction holds the claim, the business change and the mark together (invariant
    // 3): all commit or all roll back. {@link TransactionCallback#doInTransaction} cannot declare a
    // checked exception, so every {@link InboxConsumerAction#execute} call is guarded here. A
    // bounded local retry uses one savepoint, re-rolled before every attempt, so each failed
    // attempt's partial changes are undone while the claim row is preserved; on exhaustion the row
    // is recorded {@code FAILED} (never {@code PROCESSED}), held for the consumer retry / DLQ
    // policy (S06-11).
    return transactionTemplate.execute(
        (TransactionCallback<InboxConsumeResult>)
            status -> {
              // Step 1 - claim: the composite PK rejects a duplicate delivery (S06-10).
              if (!inbox.claim(claim)) {
                return InboxConsumeResult.OTHER_CLAIMED;
              }
              Object savepoint = status.createSavepoint();
              String lastError = null;
              for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                  String outcome = action.execute();
                  inbox.markProcessed(consumerName, claim.eventId(), outcome);
                  return InboxConsumeResult.PROCESSED;
                } catch (Exception failure) {
                  lastError = redact(failure);
                  // Undo this attempt's (partial) change before deciding the disposition, so a
                  // permanent fault never leaves a half-applied change committed (S06-11).
                  try {
                    status.rollbackToSavepoint(savepoint);
                  } catch (Exception rollbackError) {
                    // The retry proceeds regardless of a savepoint rollback hiccup.
                  }
                  // Classify the fault (S06-11): a permanent / malformed event short-circuits the
                  // bounded immediate retry &mdash; it is held for the DLQ, never retried, so a
                  // malformed event cannot cycle the consumer forever. A transient fault runs the
                  // bounded immediate retry up to {@code maxAttempts}.
                  if (errorPolicy.classify(failure) == InboxErrorClassification.PERMANENT) {
                    inbox.markFailed(consumerName, claim.eventId(), attempt, null, lastError);
                    return InboxConsumeResult.DEAD_LETTER;
                  }
                  if (attempt >= maxAttempts) {
                    inbox.markFailed(consumerName, claim.eventId(), attempt, null, lastError);
                    return InboxConsumeResult.RETRY;
                  }
                }
              }
              // Unreachable: the loop returns PROCESSED, DEAD_LETTER or RETRY on every path.
              return InboxConsumeResult.RETRY;
            });
  }

  /**
   * Produce a safe, redacted summary of a failure for the inbox {@code last_error} column
   * (invariant 8: never persist a secret, full prompt or full document). The exception class name
   * and a truncated, newline-stripped message are kept; callers should throw controlled domain
   * exceptions rather than ones carrying sensitive data.
   *
   * @param failure the business change failure
   * @return a short, safe, redacted summary
   */
  private static String redact(Throwable failure) {
    String message = failure.getMessage();
    String summary =
        failure.getClass().getSimpleName() + (message == null ? "" : ": " + strip(message));
    return summary.length() <= 200 ? summary : summary.substring(0, 200);
  }

  private static String strip(String value) {
    return value.replaceAll("\\s+", " ").trim();
  }
}
