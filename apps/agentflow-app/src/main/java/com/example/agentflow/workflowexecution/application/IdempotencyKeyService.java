package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.workflowexecution.persistence.IdempotentRunView;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Create-run idempotency under {@code Idempotency-Key} (S06-06).
 *
 * <p>The single owner of the keyed create-run decision (named in {@code
 * docs/architecture/module-api.md}). {@link RunService#createRun} delegates to this service so the
 * idempotency rule — look up the run by key, replay on a matching payload, conflict on a different
 * one — lives in one place, alongside the repository lookup it answers from.
 *
 * <p>The stored payload {@code fingerprint} (the lowercase-hex SHA-256 of the original request, see
 * {@code common.Fingerprint}) is what distinguishes a replay from a payload change on a reused key:
 * it is an opaque, one-way digest, so a replay is proven without ever recovering the payload, and
 * the fingerprint is never a secret, a full prompt or a full document, nor an authorization source
 * (invariant 8).
 */
@Service
public class IdempotencyKeyService {

  private final WorkflowRunRepository runRepository;

  /**
   * @param runRepository the run repository, or {@code null} when no database is configured
   */
  @Autowired(required = false)
  public IdempotencyKeyService(@Autowired(required = false) WorkflowRunRepository runRepository) {
    this.runRepository = runRepository;
  }

  /**
   * Look up the run already created under {@code key} for {@code tenantId} — the single source of
   * truth for a keyed create. The lookup is tenant-scoped (invariant 1): another tenant's row, like
   * a missing one, is indistinguishable from absent, so the caller only ever sees {@link
   * Optional#empty()} when there is no run of their own under the key.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param key the idempotency key to resolve
   * @return the stored row to replay against, or {@link Optional#empty()} when no run exists under
   *     the key
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} when no database
   *     connection is configured
   */
  public Optional<IdempotentRunView> findByIdempotencyKey(UUID tenantId, UUID key) {
    requireRepository(runRepository);
    return runRepository.findByIdempotencyKey(tenantId, key);
  }

  /**
   * Answer a keyed replay against the stored payload fingerprint: a stored run whose fingerprint
   * equals {@code incomingFingerprint} is replayed — the original {@link RunDto} is returned and
   * neither a second run nor a second event is produced, so a client that retries a lost response
   * succeeds cleanly (invariant 6); a stored run with a <em>different</em> fingerprint under the
   * same key is a permanent {@code 409 conflict}, never a silent overwrite (a reused key claims the
   * payload too).
   *
   * @param stored the run stored under the key, carrying the fingerprint it was created with
   * @param incomingFingerprint the lowercase-hex SHA-256 of the incoming request payload
   * @param key the key the replay is resolved under (recorded in the conflict message)
   * @return the original run, when the fingerprints match
   * @throws org.springframework.web.server.ResponseStatusException {@code 409 conflict} when the
   *     stored payload differs from the incoming one
   */
  public RunDto answerReplay(IdempotentRunView stored, String incomingFingerprint, UUID key) {
    if (!Objects.equals(stored.fingerprint(), incomingFingerprint)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "idempotency key " + key + " is already used with a different request payload");
    }
    return new RunDto(
        stored.runId(),
        stored.workflowVersionId(),
        stored.status(),
        stored.attempt(),
        stored.createdAt());
  }

  /**
   * @param repository the run repository, or {@code null} when no database is configured
   * @return the repository, or a {@code 503} when no database connection is configured (S01-02)
   */
  private <T> T requireRepository(T repository) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "resolving an idempotency key is unavailable: no database connection is configured");
    }
    return repository;
  }
}
