package com.example.agentflow.messaging.application;

/**
 * The business state change an inbox consumer applies when it claims an event (S06-10).
 *
 * <p>The inbox dedup helper ({@code InboxDedupService}) invokes this only after it has atomically
 * claimed the event's {@code (consumer_name, event_id)} row inside the same transaction, so the
 * change runs at most once per event per consumer even under concurrent duplicate Kafka delivery.
 * The change is written through the owning module's repository/service (for example a {@code
 * workflowexecution} run/step transition) and therefore commits or rolls back with the inbox claim.
 *
 * <p>The change must be safe to re-run after a transient failure (at-least-once delivery, ADR-003):
 * a transient failure rolls the whole claim-and-change transaction back, so nothing is marked
 * {@code PROCESSED} and the event is re-delivered for a fresh claim. Model output, uploaded
 * documents and tool results handled here are untrusted data (invariant 7).
 */
@FunctionalInterface
public interface InboxConsumerAction {

  /**
   * Apply the business state change for the claimed event.
   *
   * @return the business outcome recorded on the inbox row on success (for example the transition
   *     {@code "prior=RUNNING new=SUCCEEDED"}); never {@code null} on success
   * @throws Exception if the change fails; a transient failure is retried within the bounded retry
   *     and a permanent one is recorded as {@code FAILED} (never {@code PROCESSED})
   */
  String execute() throws Exception;
}
