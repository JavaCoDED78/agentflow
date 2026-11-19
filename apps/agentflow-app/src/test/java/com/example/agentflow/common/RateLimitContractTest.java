package com.example.agentflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code S04-16} rate-limiter port contract is a pure, storage-agnostic seam: the {@link
 * RateLimitGroup} names the four throttle boundaries and carries the canonical config key, and the
 * {@link RateLimitKey} groups strictly by {@code (tenant, principal, group)} so a tenant or
 * principal never collides (invariant 1 — the tenant is the trust anchor, never the request body).
 * The {@link RateLimiter} port itself is left unimplemented until {@code S04-17} provides an
 * in-memory / Redis adapter; it must stay free of any persistence import.
 */
class RateLimitContractTest {

  @Test
  void groups_are_the_four_closed_named_boundaries() {
    assertThat(RateLimitGroup.values())
        .extracting(RateLimitGroup::configKey)
        .containsExactlyInAnyOrder("create-run", "upload", "search/debug", "ops");
  }

  @Test
  void key_groups_by_tenant_principal_and_group_only() {
    UUID tenant = UUID.randomUUID();
    UUID otherTenant = UUID.randomUUID();
    RateLimitKey base = new RateLimitKey(tenant, "alice", RateLimitGroup.CREATE_RUN);
    RateLimitKey differentTenant =
        new RateLimitKey(otherTenant, "alice", RateLimitGroup.CREATE_RUN);
    RateLimitKey differentPrincipal = new RateLimitKey(tenant, "bob", RateLimitGroup.CREATE_RUN);
    RateLimitKey differentGroup = new RateLimitKey(tenant, "alice", RateLimitGroup.UPLOAD);

    assertThat(base).isEqualTo(base);
    assertThat(base).isNotEqualTo(differentTenant);
    assertThat(base).isNotEqualTo(differentPrincipal);
    assertThat(base).isNotEqualTo(differentGroup);
    assertThat(base).isEqualTo(new RateLimitKey(tenant, "alice", RateLimitGroup.CREATE_RUN));
  }

  @Test
  void identical_key_is_a_single_set_member() {
    UUID tenant = UUID.randomUUID();
    RateLimitKey a = new RateLimitKey(tenant, "alice", RateLimitGroup.CREATE_RUN);
    RateLimitKey b = new RateLimitKey(tenant, "alice", RateLimitGroup.CREATE_RUN);
    Set<RateLimitKey> set = new HashSet<>();
    set.add(a);
    set.add(b);
    assertThat(set).hasSize(1);
    set.add(new RateLimitKey(tenant, "bob", RateLimitGroup.CREATE_RUN));
    assertThat(set).hasSize(2);
  }
}
