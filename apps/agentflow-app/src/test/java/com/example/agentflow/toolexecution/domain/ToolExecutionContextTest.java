package com.example.agentflow.toolexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ToolExecutionContext} call context (S08-08).
 *
 * <p>They verify the acceptance criterion — <em>model arguments cannot replace the actor/tenant
 * context</em> — through the properties that make it hold: the context is built only from a
 * trusted, validated set of fields, is immutable (no mutators; the capability set is returned
 * unmodifiable and defensively copied), requires a tenant and a run, and carries no secret.
 * DB-free.
 */
class ToolExecutionContextTest {

  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RUN = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static ToolExecutionContext context(Set<String> capabilities) {
    return ToolExecutionContext.of(TENANT, "alice", RUN, "step-1", "corr-1", capabilities);
  }

  @Test
  void a_context_carries_the_tenant_actor_run_step_correlation_and_capabilities() {
    ToolExecutionContext ctx = context(Set.of("tool:calculator"));

    assertThat(ctx.tenantId()).isEqualTo(TENANT);
    assertThat(ctx.actor()).isEqualTo("alice");
    assertThat(ctx.runId()).isEqualTo(RUN);
    assertThat(ctx.stepRunId()).isEqualTo("step-1");
    assertThat(ctx.correlationId()).isEqualTo("corr-1");
    assertThat(ctx.capabilities()).containsExactly("tool:calculator");
  }

  @Test
  void a_null_tenant_run_or_capabilities_is_rejected() {
    assertThatThrownBy(
            () -> ToolExecutionContext.of(null, "alice", RUN, "step-1", "corr-1", Set.of("tool:x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId");
    assertThatThrownBy(
            () ->
                ToolExecutionContext.of(
                    TENANT, "alice", null, "step-1", "corr-1", Set.of("tool:x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId");
    assertThatThrownBy(
            () -> ToolExecutionContext.of(TENANT, "alice", RUN, "step-1", "corr-1", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capabilities");
  }

  @Test
  void an_empty_capabilities_set_is_allowed_and_grants_no_side_effect() {
    ToolExecutionContext ctx = context(Set.of());
    assertThat(ctx.capabilities()).isEmpty();
  }

  @Test
  void the_actor_step_and_corlation_may_be_absent() {
    ToolExecutionContext ctx = ToolExecutionContext.of(TENANT, null, RUN, null, null, Set.of());
    assertThat(ctx.actor()).isNull();
    assertThat(ctx.stepRunId()).isNull();
    assertThat(ctx.correlationId()).isNull();
  }

  @Test
  void the_capabilities_are_returned_unmodifiable() {
    ToolExecutionContext ctx = context(Set.of("tool:calculator"));
    // A tool adapter holding the context cannot add a capability it was not granted.
    assertThatThrownBy(() -> ctx.capabilities().add("admin:all"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void the_constructor_does_not_mutate_a_supplied_capabilities_set() {
    Set<String> mutable = new LinkedHashSet<>(Set.of("tool:calculator"));
    ToolExecutionContext ctx = context(mutable);
    // The caller (or a tool adapter) mutating the source after building the context does not change
    // it — the context kept its own copy, so the granted capability set is frozen at construction.
    mutable.add("tool:report");
    assertThat(ctx.capabilities()).containsExactly("tool:calculator");
  }

  @Test
  void value_equality_holds_for_equal_contexts() {
    assertThat(context(Set.of("tool:calculator"))).isEqualTo(context(Set.of("tool:calculator")));
    // A different tenant breaks equality — the context is scoped to one verified tenant.
    ToolExecutionContext other =
        ToolExecutionContext.of(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            "alice",
            RUN,
            "step-1",
            "corr-1",
            Set.of("tool:calculator"));
    assertThat(context(Set.of("tool:calculator"))).isNotEqualTo(other);
  }

  @Test
  void the_context_carries_no_secret_field() {
    // The S08-08 rule "do not hand out secrets to every tool adapter" — the context is the narrow
    // (tenant, actor, run, step, correlationId, capabilities) slice. Enumerate the record
    // components
    // to prove the field set is exactly that slice and none is a secret-bearing name (token, key,
    // secret, credential, prompt).
    Set<String> fields =
        java.util.Arrays.stream(ToolExecutionContext.class.getRecordComponents())
            .map(rc -> rc.getName())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "tenantId", "actor", "runId", "stepRunId", "correlationId", "capabilities");
    assertThat(fields)
        .noneMatch(
            name -> {
              String lower = name.toLowerCase();
              return lower.contains("token")
                  || lower.contains("secret")
                  || lower.contains("credential")
                  || lower.contains("prompt")
                  || lower.contains("privatekey")
                  || lower.contains("apikey");
            });
  }

  @Test
  void a_new_tenant_or_actor_requires_a_fresh_context_model_cannot_supplement_it() {
    // The only way to change who a call runs as is to build a new context — the model's arguments
    // have no accessor or mutator to alter the actor/tenant of the one handed to a tool.
    ToolExecutionContext ctx = context(Set.of("tool:calculator"));
    assertThat(ctx.actor()).isEqualTo("alice");
    assertThat(ctx.tenantId()).isEqualTo(TENANT);
    assertThat(ctx.stepRunId()).isEqualTo("step-1");
    // There is no method on the context that lets a caller overwrite the actor or tenant: only the
    // validated constructor (server-side, from the trusted context) produces a different one.
    assertThat(ToolExecutionContext.class.getMethods())
        .noneMatch(m -> m.getName().toLowerCase().startsWith("set") && m.getParameterCount() > 0);
  }
}
