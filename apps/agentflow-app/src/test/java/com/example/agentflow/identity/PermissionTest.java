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
  void catalogueHoldsTheCanonicalS04_04Ids() {
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
  void everyIdRoundTripsThroughFromId() {
    for (Permission permission : Permission.all()) {
      assertThat(Permission.fromId(permission.id())).contains(permission);
      assertThat(Permission.exists(permission.id())).isTrue();
    }
  }

  @Test
  void catalogueIdsAreUnique() {
    Set<String> ids = Permission.ids();

    assertThat(ids).hasSize(Permission.all().size());
  }

  @Test
  void unknownIdsAreRejected() {
    assertThat(Permission.fromId("workflow:approve")).isEmpty();
    assertThat(Permission.fromId("knowledge:read")).isEmpty();
    assertThat(Permission.fromId("tenant:admin")).isEmpty();
    assertThat(Permission.fromId("")).isEmpty();
    assertThat(Permission.exists("workflow:read")).isTrue();
  }

  @Test
  void everyIdIsLowercaseColonSeparated() {
    for (String id : Permission.ids()) {
      assertThat(id).matches("[a-z]+:[a-z]+");
    }
  }

  @Test
  void catalogueIsStable() {
    // Guard: adding a permission must not silently shrink the catalogue.
    assertThat(Permission.all()).hasSizeGreaterThanOrEqualTo(8);
  }
}
