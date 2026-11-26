package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the service identity the durable worker runs under (S07-18). The worker is a
 * platform service principal: a stable {@code agentflow-worker} subject for every transition and
 * audit record it writes, never a per-instance lease id and never a user token — so these tests
 * assert the stable subject, the {@code usesUserToken() == false} invariant and the M2M guard: when
 * the operator asks for the machine-to-machine client, a missing or blank secret refuses the build
 * loudly instead of starting a worker that claims to use a credential it does not hold.
 */
class ServiceIdentityTest {

  @Test
  void the_default_worker_identity_is_the_stable_subject() {
    ServiceIdentity identity = ServiceIdentity.worker();

    assertThat(identity.subject()).isEqualTo(ServiceIdentity.WORKER_SERVICE_ACTOR);
    assertThat(identity.subject()).isEqualTo("agentflow-worker");
  }

  @Test
  void the_worker_identity_never_presents_a_user_token() {
    assertThat(ServiceIdentity.worker().usesUserToken()).isFalse();
    assertThat(ServiceIdentity.worker(true, "non-blank-secret").usesUserToken()).isFalse();
  }

  @Test
  void the_worker_identity_never_carries_a_lease_identity() {
    // The stable subject is operator-independent (never the per-instance lease identity) — the
    // instance id stays on the lease ({@code lease_owner}) and never doubles as an actor.
    assertThat(ServiceIdentity.worker().subject()).doesNotContain("lease");
  }

  @Test
  void worker_with_no_m2m_client_needs_no_secret_and_constructs() {
    ServiceIdentity identity = ServiceIdentity.worker(false, null);

    assertThat(identity.subject()).isEqualTo(ServiceIdentity.WORKER_SERVICE_ACTOR);
  }

  @Test
  void worker_with_m2m_client_required_and_no_secret_refuses_to_build() {
    assertThatThrownBy(() -> ServiceIdentity.worker(true, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ServiceIdentity.M2M_CLIENT_SECRET_ENV)
        .hasMessageContaining("agentflow.worker.service-client-enabled");
  }

  @Test
  void worker_with_m2m_client_required_and_a_blank_secret_refuses_to_build() {
    assertThatThrownBy(() -> ServiceIdentity.worker(true, "   "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ServiceIdentity.M2M_CLIENT_SECRET_ENV);
  }

  @Test
  void worker_with_m2m_client_required_and_a_real_secret_constructs_the_stable_subject() {
    ServiceIdentity identity = ServiceIdentity.worker(true, "a-real-client-secret");

    assertThat(identity.subject()).isEqualTo(ServiceIdentity.WORKER_SERVICE_ACTOR);
  }
}
