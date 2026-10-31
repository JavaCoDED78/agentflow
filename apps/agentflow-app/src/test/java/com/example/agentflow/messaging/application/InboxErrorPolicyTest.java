package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for {@link InboxErrorPolicy} (S06-11): permanent faults &mdash; {@link
 * IllegalArgumentException} / {@link IllegalStateException} / {@link NullPointerException} &mdash;
 * are classified {@link InboxErrorClassification#PERMANENT} (malformed, never retried); everything
 * else, including a connection reset or socket timeout, is {@link
 * InboxErrorClassification#TRANSIENT} (retryable, bounded immediate retry). A {@code null} cause is
 * undetermined, so it is retried (never dropped). The policy is stateless, so {@link #defaults()}
 * is a shared instance.
 */
class InboxErrorPolicyTest {

  private final InboxErrorPolicy policy = InboxErrorPolicy.defaults();

  @Test
  void IllegalArgumentException_is_permanent() {
    assertThat(policy.classify(new IllegalArgumentException("malformed envelope")))
        .isEqualTo(InboxErrorClassification.PERMANENT);
  }

  @Test
  void IllegalStateException_is_permanent() {
    assertThat(policy.classify(new IllegalStateException("step out of order")))
        .isEqualTo(InboxErrorClassification.PERMANENT);
  }

  @Test
  void NullPointerException_is_permanent() {
    assertThat(policy.classify(new NullPointerException("null action")))
        .isEqualTo(InboxErrorClassification.PERMANENT);
  }

  @Test
  void generic_runtime_exception_is_transient() {
    assertThat(policy.classify(new RuntimeException("transient broker fault")))
        .isEqualTo(InboxErrorClassification.TRANSIENT);
  }

  @Test
  void connection_reset_is_transient() {
    assertThat(policy.classify(new SocketException("Connection reset")))
        .isEqualTo(InboxErrorClassification.TRANSIENT);
  }

  @Test
  void socket_timeout_is_transient() {
    assertThat(policy.classify(new SocketTimeoutException("Read timed out")))
        .isEqualTo(InboxErrorClassification.TRANSIENT);
  }

  @Test
  void throttling_is_transient_when_not_in_the_permitted_permanent_set() {
    // Throttling is not in the permanent set, so (by policy) it is transient and retried, bounded
    // (S06-11). A future throttling-specific exception added to the policy would change this.
    assertThat(policy.classify(new RuntimeException("throttled")))
        .isEqualTo(InboxErrorClassification.TRANSIENT);
  }

  @Test
  void undetermined_null_cause_is_transient_and_retried() {
    // A null cause cannot be classified, so it is treated as transient: retried (bounded) rather
    // than silently dropped (invariant 3 / S06-11).
    assertThat(policy.classify(null)).isEqualTo(InboxErrorClassification.TRANSIENT);
  }

  @Test
  void defaults_is_stateless_and_shared() {
    // Stateless single instance: classify is deterministic regardless of call order / state.
    assertThat(policy).isSameAs(InboxErrorPolicy.defaults());
    assertThat(policy.classify(new RuntimeException("transient broker fault")))
        .isEqualTo(policy.classify(new RuntimeException("transient broker fault")));
  }
}
