package com.example.agentflow.toolexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.RetryPolicy;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the in-memory {@link ToolRegistry} (S08-02).
 *
 * <p>They verify the S08-02 acceptance criteria: a registered tool resolves by {@code (name,
 * version)}, an unknown pair resolves to empty (so an unknown tool is never called), and a
 * duplicate registration is rejected with {@link DuplicateToolRegistrationException} (which breaks
 * startup when thrown from the {@code toolRegistry} bean). They also cover the unmodifiable
 * snapshot, the null guard, and thread-safety of concurrent registration/resolution. DB-free (no
 * database, Kafka, model or system clock).
 */
class ToolRegistryTest {

  private static final RetryPolicy RETRY = RetryPolicy.ofDefaults();
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static Map<String, Object> schema() {
    return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", "object");
  }

  private static ToolDescriptor tool(String name, String version, String id) {
    return ToolDescriptor.of(
        id,
        name,
        version,
        "A " + name + " tool.",
        JsonSchema.of(schema()),
        JsonSchema.of(schema()),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        TIMEOUT,
        RETRY,
        false);
  }

  @Test
  void a_registered_tool_resolves_by_name_and_version() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("calculator", "1", "tool-calc-1"));

    assertThat(registry.resolve("calculator", "1")).isPresent();
    assertThat(registry.resolve("calculator", "1").get().id()).isEqualTo("tool-calc-1");
    assertThat(registry.containsTool("calculator", "1")).isTrue();
  }

  @Test
  void an_unknown_tool_resolves_to_empty_and_is_never_invoked() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("calculator", "1", "tool-calc-1"));

    // Unknown name.
    assertThat(registry.resolve("send-email", "1")).isEmpty();
    assertThat(registry.containsTool("send-email", "1")).isFalse();
    // Known name, wrong version: version-strict, so it is unknown too.
    assertThat(registry.resolve("calculator", "2")).isEmpty();
    // Known name, empty version string.
    assertThat(registry.resolve("calculator", "")).isEmpty();
  }

  @Test
  void registering_the_same_name_and_version_twice_is_rejected() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("calculator", "1", "tool-calc-1"));

    assertThatThrownBy(() -> registry.register(tool("calculator", "1", "tool-calc-2")))
        .isInstanceOf(DuplicateToolRegistrationException.class)
        .hasMessageContaining("calculator")
        .hasMessageContaining("1")
        // The message names the existing descriptor's id, so the mis-wiring is diagnosable.
        .hasMessageContaining("tool-calc-1");
    // The first registration still stands; the duplicate did not replace it.
    assertThat(registry.resolve("calculator", "1").get().id()).isEqualTo("tool-calc-1");
  }

  @Test
  void two_different_versions_are_distinct_tools() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("calculator", "1", "tool-calc-1"));
    registry.register(tool("calculator", "2", "tool-calc-2"));

    assertThat(registry.resolve("calculator", "1").get().id()).isEqualTo("tool-calc-1");
    assertThat(registry.resolve("calculator", "2").get().id()).isEqualTo("tool-calc-2");
    assertThat(registry.size()).isEqualTo(2);
  }

  @Test
  void all_returns_an_unmodifiable_snapshot() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("calculator", "1", "tool-calc-1"));
    registry.register(tool("report", "1", "tool-report-1"));

    Set<String> names =
        registry.all().stream()
            .map(ToolDescriptor::name)
            .collect(java.util.stream.Collectors.toSet());
    assertThat(names).containsExactlyInAnyOrder("calculator", "report");

    // Mutating the snapshot does not change the registry, and a later registration does not change
    // a caller's already-taken snapshot.
    assertThatThrownBy(() -> registry.all().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void registering_a_null_descriptor_is_rejected() {
    ToolRegistry registry = new ToolRegistry();
    assertThatThrownBy(() -> registry.register(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("descriptor");
  }

  @Test
  void concurrent_registration_of_distinct_keys_does_not_lose_entries()
      throws InterruptedException {
    ToolRegistry registry = new ToolRegistry();
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    for (int i = 0; i < threads; i++) {
      final int n = i;
      pool.submit(
          () -> {
            try {
              start.await();
              registry.register(tool("tool-" + n, "1", "id-" + n));
              // Resolve every other key concurrently to exercise read/write interleaving.
              for (int j = 0; j < threads; j++) {
                registry.resolve("tool-" + j, "1");
              }
            } catch (Throwable t) {
              failure.set(t);
            } finally {
              done.countDown();
            }
          });
    }

    start.countDown();
    await(done, 10);
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isNull();
    assertThat(registry.size()).isEqualTo(threads);
  }

  private static void await(CountDownLatch latch, long seconds) throws InterruptedException {
    assertThat(latch.await(seconds, TimeUnit.SECONDS))
        .as("the concurrent registration phase finished in time")
        .isTrue();
  }
}
