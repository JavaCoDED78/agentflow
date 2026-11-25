package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.messaging.application.InboxConsumeResult;
import com.example.agentflow.messaging.application.InboxConsumerAction;
import com.example.agentflow.messaging.application.InboxDedupService;
import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Messaging consistency acceptance tests (S14-04) on <b>real PostgreSQL</b> (Testcontainers),
 * driving the production inbox dedup boundary and the real run transition service together.
 *
 * <p>The guarantee under test is the Sprint 14 acceptance criterion — <em>no double business
 * effects and no forbidden transitions</em> under at-least-once delivery (ADR-003):
 *
 * <ul>
 *   <li><b>Duplicates</b> — the same {@code event_id} delivered repeatedly must produce exactly one
 *       business effect: one claim, one run transition, one {@code workflow_events} history row and
 *       one {@code PROCESSED} inbox row; every redelivery is skipped.
 *   <li><b>Reordered events of one run</b> — events of the same run delivered out of order must
 *       never apply a transition the state machine refuses: the run never moves to a state its
 *       guard forbids ({@code QUEUED -> SUCCEEDED} has no edge), the forbidden attempt commits
 *       nothing, and the one legal event still applies, so the run ends in the state the legal
 *       sequence reaches — never in an invalid intermediate or terminal state.
 * </ul>
 *
 * <p>Events are modelled at the inbox boundary (not at the broker): an event of a run is the
 * transition its envelope carries ({@code run.started} = {@code QUEUED -> RUNNING}, {@code
 * run.succeeded} = {@code RUNNING -> SUCCEEDED}), each delivered through {@link
 * InboxDedupService#consume} with a fresh claim — the same seam the production consumers ({@code
 * DocumentIndexingWorker}, the workflow execution path) use. The business action is the real {@link
 * RunStateMachineService} (the single transition point, invariant 4), so a guarded transition is
 * refused by the production domain gate, not by a test-local re-check.
 *
 * <p>A fresh tenant per test keeps the three tests independent on the shared container; every
 * assertion is scoped by {@code event_id} / {@code run_id}, never by the shared consumer name.
 */
@Testcontainers
class DuplicateAndReorderedEventsIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static final String CONSUMER = "workflowexecution:run-transitions";

  private static JdbcTemplate jdbc;
  private static DataSource dataSource;

  @BeforeAll
  static void migrateAndWire() throws SQLException {
    // Flyway creates tenants / workflow_definitions / workflow_runs / workflow_events /
    // inbox_messages exactly as CI does. Idempotent on the repeatable @BeforeAll run.
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  // --- duplicates ---------------------------------------------------------------------------

  /**
   * S14-04 (duplicates): the same {@code event_id} delivered five times must drive the business
   * transition exactly once — the first claim proceeds, the rest are {@link
   * InboxConsumeResult#OTHER_CLAIMED}, and precisely one history row and one {@code PROCESSED}
   * inbox row exist.
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void same_event_id_delivered_repeatedly_runs_the_business_action_exactly_once() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    UUID eventId = UUID.randomUUID();

    WorkflowEventService events = newWorkflowEventService();
    RunStateMachineService service = newRunStateMachineService(events);
    InboxDedupService inbox = newInbox(3);

    AtomicInteger effects = new AtomicInteger();
    InboxConsumerAction action =
        () -> {
          service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.started", "worker");
          effects.incrementAndGet();
          return "prior=QUEUED new=RUNNING";
        };

    List<InboxConsumeResult> results = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      results.add(inbox.consume(CONSUMER, InboxMessage.claiming(CONSUMER, eventId), action));
    }

    // first claim proceeds; every redelivery is skipped by the dedup fence
    assertThat(results.get(0)).isEqualTo(InboxConsumeResult.PROCESSED);
    for (int i = 1; i < 5; i++) {
      assertThat(results.get(i)).isEqualTo(InboxConsumeResult.OTHER_CLAIMED);
    }
    // the business effect ran exactly once — never five times, never four times
    assertThat(effects.get()).isEqualTo(1);
    // the run moved QUEUED -> RUNNING exactly once: one history row, no second transition
    assertThat(events.historyByRun(tenant, runId)).hasSize(1);
    assertThat(events.historyByRun(tenant, runId).get(0).eventType()).isEqualTo("run.started");
    // exactly one inbox row for the pair, PROCESSED (no PENDING residue, no duplicate row)
    InboxMessage row = inboxRow(eventId);
    assertThat(row.status()).isEqualTo(InboxMessage.Status.PROCESSED);
    assertThat(runState(tenant, runId).status()).isEqualTo(WorkflowRunState.RUNNING);
  }

  // --- reordered events ----------------------------------------------------------------------

  /**
   * S14-04 (reordered): the events of one run delivered in reverse order — the terminal event first
   * — must not apply a forbidden transition. The refused event commits nothing (the run never
   * reaches {@code SUCCEEDED}), its inbox row is held as {@code FAILED} for the consumer retry /
   * DLQ policy, and the legal event still applies, so the run ends in the state the legal sequence
   * reaches ({@code RUNNING}) with exactly one history row.
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void events_of_one_run_delivered_out_of_order_never_apply_a_forbidden_transition() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    UUID startedEventId = UUID.randomUUID();
    UUID succeededEventId = UUID.randomUUID();

    WorkflowEventService events = newWorkflowEventService();
    RunStateMachineService service = newRunStateMachineService(events);
    // a bounded immediate retry of two attempts, so the refusal is recorded deterministically
    InboxDedupService inbox = newInbox(2);

    // 1 - the later event arrives first: run.succeeded (RUNNING -> SUCCEEDED) while the run is
    // QUEUED. QUEUED -> SUCCEEDED has no edge, the production domain gate refuses it (400).
    InboxConsumeResult refused =
        inbox.consume(
            CONSUMER,
            InboxMessage.claiming(CONSUMER, succeededEventId),
            () -> {
              service.transitionRun(
                  tenant, runId, WorkflowRunState.SUCCEEDED, "run.succeeded", "worker");
              return "prior=RUNNING new=SUCCEEDED";
            });

    // 2 - the earlier event arrives: run.started (QUEUED -> RUNNING) while the run is still
    // QUEUED. QUEUED -> RUNNING is an edge, so it applies.
    InboxConsumeResult applied =
        inbox.consume(
            CONSUMER,
            InboxMessage.claiming(CONSUMER, startedEventId),
            () -> {
              service.transitionRun(
                  tenant, runId, WorkflowRunState.RUNNING, "run.started", "worker");
              return "prior=QUEUED new=RUNNING";
            });

    assertThat(refused).isEqualTo(InboxConsumeResult.RETRY);
    assertThat(applied).isEqualTo(InboxConsumeResult.PROCESSED);

    // the forbidden transition was never applied: the run is RUNNING, never SUCCEEDED
    assertThat(runState(tenant, runId).status()).isEqualTo(WorkflowRunState.RUNNING);
    // exactly one history row — run.started — the refused run.succeeded left nothing
    assertThat(events.historyByRun(tenant, runId)).hasSize(1);
    assertThat(events.historyByRun(tenant, runId).get(0).eventType()).isEqualTo("run.started");

    // the refused event's inbox row is FAILED — held for the consumer retry / DLQ policy, never
    // PROCESSED — with the bounded retry recorded
    InboxMessage refusedRow = inboxRow(succeededEventId);
    assertThat(refusedRow.status()).isEqualTo(InboxMessage.Status.FAILED);
    assertThat(refusedRow.attempts()).isEqualTo(2);
    // the legal event's row is PROCESSED
    InboxMessage appliedRow = inboxRow(startedEventId);
    assertThat(appliedRow.status()).isEqualTo(InboxMessage.Status.PROCESSED);
  }

  /**
   * S14-04 (the valid counterpart): the same two events delivered in order — the legal sequence of
   * one run — both apply and the run reaches the terminal {@code SUCCEEDED} state with exactly two
   * history rows. This proves the guards permit the legal order and that the inbox boundary drives
   * a complete run end to end.
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void events_of_one_run_delivered_in_order_apply_both_transitions_and_reach_the_terminal_state() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    UUID startedEventId = UUID.randomUUID();
    UUID succeededEventId = UUID.randomUUID();

    WorkflowEventService events = newWorkflowEventService();
    RunStateMachineService service = newRunStateMachineService(events);
    InboxDedupService inbox = newInbox(2);

    InboxConsumeResult started =
        inbox.consume(
            CONSUMER,
            InboxMessage.claiming(CONSUMER, startedEventId),
            () -> {
              service.transitionRun(
                  tenant, runId, WorkflowRunState.RUNNING, "run.started", "worker");
              return "prior=QUEUED new=RUNNING";
            });
    InboxConsumeResult succeeded =
        inbox.consume(
            CONSUMER,
            InboxMessage.claiming(CONSUMER, succeededEventId),
            () -> {
              service.transitionRun(
                  tenant, runId, WorkflowRunState.SUCCEEDED, "run.succeeded", "worker");
              return "prior=RUNNING new=SUCCEEDED";
            });

    assertThat(started).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(succeeded).isEqualTo(InboxConsumeResult.PROCESSED);

    // the legal sequence is fully applied: both history rows, terminal state, both inbox rows
    assertThat(runState(tenant, runId).status()).isEqualTo(WorkflowRunState.SUCCEEDED);
    List<String> historyTypes =
        events.historyByRun(tenant, runId).stream().map(e -> e.eventType()).sorted().toList();
    assertThat(historyTypes).containsExactly("run.started", "run.succeeded");
    assertThat(inboxRow(startedEventId).status()).isEqualTo(InboxMessage.Status.PROCESSED);
    assertThat(inboxRow(succeededEventId).status()).isEqualTo(InboxMessage.Status.PROCESSED);
  }

  // --- wiring and fixtures --------------------------------------------------------------------

  private WorkflowEventService newWorkflowEventService() {
    return new WorkflowEventService(new WorkflowEventRepository(jdbc));
  }

  private RunStateMachineService newRunStateMachineService(WorkflowEventService events) {
    return new RunStateMachineService(new WorkflowRunRepository(jdbc), null, null, events, null);
  }

  private InboxDedupService newInbox(int maxAttempts) {
    return new InboxDedupService(
        new InboxMessageRepository(jdbc),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        maxAttempts);
  }

  /** The inbox row for one {@code event_id} through the production repository. */
  private InboxMessage inboxRow(UUID eventId) {
    var row = new InboxMessageRepository(jdbc).findByConsumerAndEvent(CONSUMER, eventId);
    assertThat(row).as("inbox row for event %s must exist", eventId).isPresent();
    return row.get();
  }

  private RunStateSnapshot runState(UUID tenant, UUID runId) {
    return new WorkflowRunRepository(jdbc)
        .findState(tenant, runId)
        .orElseThrow(() -> new AssertionError("run " + runId + " missing"));
  }

  /** Insert one {@code QUEUED} run for a fresh tenant (the S05-11 seeding pattern). */
  private static void seedRun(UUID tenant, UUID runId) {
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, definitionId);
          ps.setObject(2, tenant);
          ps.setString(3, "wf-" + runId);
        });
    jdbc.update(
        "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?::jsonb, ?)",
        ps -> {
          ps.setObject(1, versionId);
          ps.setObject(2, definitionId);
          ps.setString(3, "{\"nodes\":[]}");
          ps.setString(4, "c");
        });
    jdbc.update(
        "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status) VALUES (?, ?, ?, 'QUEUED')",
        ps -> {
          ps.setObject(1, runId);
          ps.setObject(2, tenant);
          ps.setObject(3, versionId);
        });
  }
}
