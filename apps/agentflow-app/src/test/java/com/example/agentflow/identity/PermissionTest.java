package com.example.agentflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.identity.domain.Permission;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the S04-04 permission catalogue. The catalogue is the single source of truth for
 * the {@code authorization} id, so it is closed and deterministic: every id is unique, {@link
 * Permission#fromId(String)} round-trips, and unknown ids are rejected.
 */
class PermissionTest {

  @Test
  void catalogue_holds_the_canonical_s04_04_ids() {
    Set<String> ids = Permission.ids();

    assertThat(ids)
        .containsExactlyInAnyOrder(
            "workflow:read",
            "workflow:write",
            "workflow:activate",
            "document:read",
            "document:write",
            "run:execute",
            "run:approve",
            "ops:dlq");
  }

  @Test
  void every_id_round_trips_through_from_id() {
    for (Permission permission : Permission.all()) {
      assertThat(Permission.fromId(permission.id())).contains(permission);
      assertThat(Permission.exists(permission.id())).isTrue();
    }
  }

  @Test
  void catalogue_ids_are_unique() {
    Set<String> ids = Permission.ids();

    assertThat(ids).hasSize(Permission.all().size());
  }

  @Test
  void unknown_ids_are_rejected() {
    assertThat(Permission.fromId("workflow:approve")).isEmpty();
    assertThat(Permission.fromId("knowledge:read")).isEmpty();
    assertThat(Permission.fromId("tenant:admin")).isEmpty();
    assertThat(Permission.fromId("")).isEmpty();
    assertThat(Permission.exists("workflow:read")).isTrue();
  }

  @Test
  void every_id_is_lowercase_colon_separated() {
    for (String id : Permission.ids()) {
      assertThat(id).matches("[a-z]+:[a-z]+");
    }
  }

  @Test
  void catalogue_is_stable() {
    // Guard: adding a permission must not silently shrink the catalogue.
    assertThat(Permission.all()).hasSizeGreaterThanOrEqualTo(8);
  }
}
