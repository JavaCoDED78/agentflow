package com.example.agentflow.workflowexecution.application;

/**
 * The service identity the durable worker runs under (S07-18: durable execution — service identity
 * for the worker).
 *
 * <p>The worker never acts as a user: every transition it performs (run/step state moves through
 * {@link RunStateMachineService}), every row it writes ({@code workflow_step_runs}, {@code
 * approvals}, {@code workflow_events}) and every audit row it appends goes out under one stable,
 * operator-independent {@code actor_subject} — {@link #WORKER_SERVICE_ACTOR}. The worker also holds
 * no user token: the identity is a platform service principal, never a user session, so no user
 * token (or client secret) is ever threaded into Kafka payloads, log lines or audit metadata
 * (invariant 8). The human who started the run is never the worker's actor — the run row keeps that
 * requester in {@code workflow_runs.created_by}, and the system's audit records carry it in
 * redacted metadata.
 *
 * <p>The machine-to-machine (M2M) client of the {@code agentflow-service} Keycloak client (S04-02)
 * is <em>not</em> required today: the worker performs no outbound call (the deterministic handler
 * is side-effect free), so it issues no token and holds none. The decision is explicit and recorded
 * as {@code usesUserToken() == false}. If a future worker bumps {@code
 * agentflow.worker.service-client-enabled} to ask for the M2M client, this factory validates the
 * secret up front and refuses to construct — a clear startup failure, never a silent run without
 * the credential it claims to use. The secret itself is read from the environment ({@link
 * #M2M_CLIENT_SECRET_ENV}), never from configuration or source (invariant 8).
 */
public final class ServiceIdentity {

  /**
   * The stable service principal subject the worker runs under. It is operator-independent: the
   * same value for every worker instance and every tenant, deliberately not the per-instance lease
   * identity — that stays on the lease ({@code lease_owner}) and never doubles as an actor.
   */
  public static final String WORKER_SERVICE_ACTOR = "agentflow-worker";

  /**
   * The environment variable that carries the {@code agentflow-service} machine-to-machine client
   * secret. The value is never read into configuration or source; it is read from the environment
   * at worker construction and used only while a protected call is being made.
   */
  public static final String M2M_CLIENT_SECRET_ENV = "AGENTFLOW_SERVICE_CLIENT_SECRET";

  private final String subject;

  private ServiceIdentity(String subject) {
    this.subject = subject;
  }

  /**
   * The stable service principal for the worker, with no M2M client (the default).
   *
   * @return the stable service identity
   */
  public static ServiceIdentity worker() {
    return new ServiceIdentity(WORKER_SERVICE_ACTOR);
  }

  /**
   * The stable service principal for the worker, validated against the operator's M2M decision.
   *
   * @param requiresM2mClient whether the operator asked the worker to call protected APIs or
   *     external services through the {@code agentflow-service} M2M client (bound from {@code
   *     agentflow.worker.service-client-enabled})
   * @param m2mClientSecret the M2M client secret (from the environment), or {@code null} when
   *     disabled — ignored when {@code requiresM2mClient} is {@code false}
   * @return the stable service identity
   * @throws IllegalStateException when the M2M client is required but the secret is absent or blank
   *     — a clear startup failure, not a silent run without the credential it claims to use
   */
  public static ServiceIdentity worker(boolean requiresM2mClient, String m2mClientSecret) {
    if (requiresM2mClient && (m2mClientSecret == null || m2mClientSecret.isBlank())) {
      throw new IllegalStateException(
          "agentflow.worker.service-client-enabled=true requires the "
              + M2M_CLIENT_SECRET_ENV
              + " environment variable to carry the agentflow-service machine-to-machine client secret; refusing to start");
    }
    return new ServiceIdentity(WORKER_SERVICE_ACTOR);
  }

  /**
   * The stable service principal subject.
   *
   * @return the stable service actor subject (the {@code actor_subject} on system transitions and
   *     audit rows)
   */
  public String subject() {
    return subject;
  }

  /**
   * Whether this identity presents a user token to any system. It never does: the worker is a
   * platform service principal, never a user session, and no user token is ever threaded into Kafka
   * payloads, log lines or audit metadata (invariant 8).
   *
   * @return always {@code false}
   */
  public boolean usesUserToken() {
    return false;
  }
}
