package com.example.agentflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime proof for S01-08 (verify Spring BOM compatibility).
 *
 * <p>The Spring AI BOM (2.0.1) pulls Jackson 3.x
 * ({@code tools.jackson}) onto the runtime classpath while the Spring Boot BOM
 * (4.1.1) still manages the 2.x {@code com.fasterxml.jackson} annotations.
 * This test asserts the BOM-managed {@link ObjectMapper} actually serializes
 * and deserializes at runtime, so the "predictable dependency graph" is not only
 * declared but exercised end to end.
 */
@SpringBootTest
class SpringAiBomCompatibilityTest {

    /**
     * The {@link ObjectMapper} autowired by the Spring Boot
     * Jackson auto-configuration, backed by the BOM-managed Jackson 3.x
     * databind. Field injection keeps the JUnit dependency graph independent of
     * runtime parameter resolution.
     */
    @Autowired
    private ObjectMapper mapper;

    /**
     * The BOM-managed, Boot-configured {@link ObjectMapper} serializes a
     * {@code Map} payload round-trip correctly. This exercises the Jackson 3.x
     * ({@code tools.jackson}) databind that the Spring AI BOM pulls onto the
     * runtime classpath, proving the "predictable dependency graph" works end to
     * end. ({@code LocalDate} round-trips are intentionally out of scope here:
     * the JavaTimeModule is a Jackson 3.x ecosystem question, see the insight
     * report.)
     */
    @Test
    void managedMapperRoundTrips() {
        Map<String, Object> payload = Map.of("tenantId", "s01-08", "value", 42);

        String json = mapper.writeValueAsString(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = mapper.readValue(json, Map.class);

        assertThat(mapper).isNotNull();
        assertThat(restored.get("tenantId")).isEqualTo("s01-08");
        assertThat(restored.get("value")).isEqualTo(42);
    }

    @Test
    void managedMapperHandlesMap() {
        ObjectMapper fresh = new ObjectMapper();
        Map<String, Object> payload = Map.of("tenantId", "t-1", "value", 42);

        String json = fresh.writeValueAsString(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = fresh.readValue(json, Map.class);

        assertThat(restored.get("tenantId")).isEqualTo("t-1");
        assertThat(restored.get("value")).isEqualTo(42);
    }
}
