package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.workflowexecution.persistence.AttemptHistoryRow;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AttemptRecord#fromRow}: the single mapping from a persistence row (S07-14) — it
 * copies the attempt identity and the safe fault metadata verbatim, and derives {@code
 * durationSeconds} from {@code started_at}/{@code finished_at} (invariant 10 — derived, never
 * stored), reporting {@code 0} while the attempt is still in flight.
 */
class AttemptRecordTest {

  private static final OffsetDateTime START =
      OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime FINISH = START.plus(Duration.ofSeconds(90));

  private static AttemptHistoryRow row(
      int attempt, OffsetDateTime finishedAt, String errorClass, String errorSummary) {
    return new AttemptHistoryRow(
        attempt,
        "send_email",
        "worker-1",
        "SUCCEEDED",
        START,
        finishedAt,
        errorClass,
        errorSummary);
  }

  @Test
  void fromRow_maps_the_identity_and_the_safe_error_category() {
    AttemptRecord record = AttemptRecord.fromRow(row(2, FINISH, "transient_infra", "upstream 503"));

    assertThat(record.attempt()).isEqualTo(2);
    assertThat(record.stepKey()).isEqualTo("send_email");
    assertThat(record.workerInstance()).isEqualTo("worker-1");
    assertThat(record.outcome()).isEqualTo("SUCCEEDED");
    assertThat(record.startedAt()).isEqualTo(START);
    assertThat(record.finishedAt()).isEqualTo(FINISH);
    // The safe error category: the ErrorClass token plus a short redacted reason — never the
    // raw error and never the secret payload (invariant 8 / S07-14).
    assertThat(record.errorClass()).isEqualTo("transient_infra");
    assertThat(record.errorSummary()).isEqualTo("upstream 503");
  }

  @Test
  void fromRow_derives_duration_from_start_and_finish_in_whole_seconds() {
    AttemptRecord record = AttemptRecord.fromRow(row(1, FINISH, null, null));

    assertThat(record.durationSeconds()).isEqualTo(90);
  }

  @Test
  void fromRow_reports_zero_duration_while_the_attempt_is_in_flight() {
    // finishedAt null: the attempt is still RUNNING, so there is no duration to report (invariant
    // 10 — derived, 0 while in flight).
    AttemptRecord record = AttemptRecord.fromRow(row(1, null, null, null));

    assertThat(record.finishedAt()).isNull();
    assertThat(record.durationSeconds()).isZero();
  }
}
