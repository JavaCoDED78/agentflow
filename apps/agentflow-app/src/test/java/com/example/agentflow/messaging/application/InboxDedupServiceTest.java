package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB-free tests for {@link InboxDedupService} (S06-10): a mocked {@link TransactionTemplate} drives
 * its {@link TransactionCallback} against a mocked {@link TransactionStatus}, and the {@link
 * InboxMessageRepository} is a mock that records the claim/mark transitions. The service is
 * exercised at the control-flow level: the claim runs first, the business change runs at most once,
 * and a transient failure never marks {@code PROCESSED} (invariant 3).
 */
class InboxDedupServiceTest {

  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final TransactionStatus status = mock(TransactionStatus.class);
  private final InboxMessageRepository inbox = mock(InboxMessageRepository.class);

  private final InboxMessage claim = InboxMessage.claiming("agent:run:step", UUID.randomUUID());

  @BeforeEach
  void setUpTx() {
    // The transactional boundary: run the callback (claim -> business change -> mark) synchronously
    // against the mocked status. Commit/rollback of a real transaction is out of scope for a
    // DB-free
    // control-flow test.
    when(tx.execute(any(TransactionCallback.class)))
        .thenAnswer(
            inv ->
                ((TransactionCallback<InboxConsumeResult>) inv.getArgument(0))
                    .doInTransaction(status));
  }

  private InboxDedupService serviceWithMaxAttempts(int maxAttempts) {
    return new InboxDedupService(inbox, tx, maxAttempts);
  }

  @Test
  void consume_runs_claim_then_business_change_then_mark_processed_once() {
    when(inbox.claim(any(InboxMessage.class))).thenReturn(true);
    doNothing().when(inbox).markProcessed(anyString(), any(), anyString());

    InboxConsumeResult result =
        serviceWithMaxAttempts(1)
            .consume("agent:run:step", claim, () -> "prior=RUNNING new=SUCCEEDED");

    assertThat(result).isEqualTo(InboxConsumeResult.PROCESSED);
    verify(inbox).claim(claim);
    verify(inbox, never()).markFailed(anyString(), any(), anyInt(), any(), anyString());
    verify(inbox).markProcessed(eq("agent:run:step"), any(), eq("prior=RUNNING new=SUCCEEDED"));
  }

  @Test
  void consume_when_a_duplicate_already_claimed_skips_the_action_and_does_not_mark_processed() {
    when(inbox.claim(any(InboxMessage.class))).thenReturn(false); // the pair already exists

    InboxConsumeResult result =
        serviceWithMaxAttempts(1)
            .consume("agent:run:step", claim, () -> "prior=RUNNING new=SUCCEEDED");

    assertThat(result).isEqualTo(InboxConsumeResult.OTHER_CLAIMED);
    verify(inbox).claim(claim);
    // the business change ran on the FIRST delivery only: the duplicate re-runs neither it nor its
    // side effect (S06-10 acceptance criterion)
    verify(inbox, never()).markProcessed(any(), any(), anyString());
  }

  @Test
  void consume_transient_failure_marks_failed_and_never_marks_processed() {
    when(inbox.claim(any(InboxMessage.class))).thenReturn(true);
    doNothing().when(inbox).markFailed(anyString(), any(), anyInt(), any(), anyString());
    when(status.createSavepoint()).thenReturn(new Object());
    doNothing().when(status).rollbackToSavepoint(any());
    InboxConsumerAction failing =
        () -> {
          throw new RuntimeException("transient broker fault");
        };

    // the single attempt failed; the bounded local retry rolls the (partial) change back to the
    // savepoint and records the row FAILED (never PROCESSED), held for the retry / DLQ policy.
    InboxConsumeResult result = serviceWithMaxAttempts(1).consume("agent:run:step", claim, failing);

    assertThat(result).isEqualTo(InboxConsumeResult.FAILED);
    verify(inbox).claim(claim);
    verify(inbox, never()).markProcessed(any(), any(), anyString());
    verify(inbox).markFailed(eq("agent:run:step"), any(), anyInt(), any(), anyString());
    verify(status, atLeastOnce()).createSavepoint();
    verify(status, atLeastOnce()).rollbackToSavepoint(any());
  }

  @Test
  void consume_bounded_retry_retries_a_transient_failure_then_succeeds() {
    when(inbox.claim(any(InboxMessage.class))).thenReturn(true);
    doNothing().when(inbox).markProcessed(anyString(), any(), anyString());
    doNothing().when(inbox).markFailed(anyString(), any(), anyInt(), any(), anyString());
    when(status.createSavepoint()).thenReturn(new Object());
    doNothing().when(status).rollbackToSavepoint(any());

    AtomicInteger calls = new AtomicInteger();
    InboxConsumeResult result =
        serviceWithMaxAttempts(3)
            .consume(
                "agent:run:step",
                claim,
                () -> {
                  if (calls.incrementAndGet() < 3) {
                    throw new RuntimeException("transient broker fault");
                  }
                  return "prior=RUNNING new=SUCCEEDED";
                });

    assertThat(result).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(calls.get()).isEqualTo(3);
    // a savepoint was created, and each failed attempt was rolled back to it before the retry
    verify(status, atLeastOnce()).createSavepoint();
    verify(status, atLeastOnce()).rollbackToSavepoint(any());
    // eventually processed, never failed
    verify(inbox).markProcessed(eq("agent:run:step"), any(), eq("prior=RUNNING new=SUCCEEDED"));
    verify(inbox, never()).markFailed(anyString(), any(), anyInt(), any(), anyString());
  }

  @Test
  void consume_bounded_retry_exhausted_marks_failed_and_never_marks_processed() {
    when(inbox.claim(any(InboxMessage.class))).thenReturn(true);
    doNothing().when(inbox).markProcessed(anyString(), any(), anyString());
    doNothing().when(inbox).markFailed(anyString(), any(), anyInt(), any(), anyString());
    when(status.createSavepoint()).thenReturn(new Object());
    doNothing().when(status).rollbackToSavepoint(any());

    InboxConsumeResult result =
        serviceWithMaxAttempts(2)
            .consume(
                "agent:run:step",
                claim,
                () -> {
                  throw new RuntimeException("transient broker fault");
                });

    // the bounded local retry was exhausted; the row is FAILED (never PROCESSED) for the retry /
    // DLQ
    assertThat(result).isEqualTo(InboxConsumeResult.FAILED);
    verify(inbox, never()).markProcessed(any(), any(), anyString());
    verify(inbox).markFailed(eq("agent:run:step"), any(), anyInt(), any(), anyString());
  }
}
