package com.example.agentflow.messaging.persistence;

/**
 * The outbox pending snapshot (S13-12): a single bounded read of the rows the publisher would claim
 * — pending, due-for-retry or stale-claimed.
 *
 * <p>{@code pendingEvents} is the count of rows in the due set. {@code oldestAgeSeconds} is the age
 * of the oldest such row (from its {@code created_at}), clamped to zero; {@code 0.0} when the set
 * is empty. Both values are \emph{queries against the database at scrape time}, not cached
 * counters, so a scrape reflects the live outbox — never a stale snapshot.
 *
 * @param pendingEvents the number of rows due for publish (PENDING due / FAILED due / CLAIMED
 *     stale)
 * @param oldestAgeSeconds the age of the oldest due row, in seconds (0 when none)
 */
public record OutboxPendingSnapshot(long pendingEvents, double oldestAgeSeconds) {}
