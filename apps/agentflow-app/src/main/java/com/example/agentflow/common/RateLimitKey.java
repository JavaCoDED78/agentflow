package com.example.agentflow.common;

import java.util.UUID;

/**
 * The rate limiter port key: the {@code (tenant, principal)} identity throttled for a {@link
 * RateLimitGroup}. The tenant and principal come only from the trusted security context, never from
 * the request body, query or headers &mdash; invariant 1 (a guessed principal or a spoofed tenant
 * changes nothing). Two keys differ only when the tenant, the principal, or the group differs.
 */
public record RateLimitKey(UUID tenantId, String principal, RateLimitGroup group) {}
