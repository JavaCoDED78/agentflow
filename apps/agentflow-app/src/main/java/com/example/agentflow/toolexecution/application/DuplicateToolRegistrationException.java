package com.example.agentflow.toolexecution.application;

/**
 * Thrown when a {@link ToolDescriptor} is registered for a {@code (name, version)} pair that is
 * already registered ({@code S08-02}).
 *
 * <p>It is an {@link IllegalStateException} — a configuration/startup error, not a per-request
 * fault — because registration happens once at startup, in platform code, and a duplicate here
 * means the application was mis-wired. The wiring that owns registration (the {@code toolRegistry}
 * bean, {@code ToolRegistryConfiguration}) therefore lets this exception propagate out of the
 * {@code @Bean} factory method: the Spring context fails to start, so a duplicate tool registration
 * <em>clearly breaks startup</em> instead of silently keeping one of the two.
 *
 * <p>The message names the offending pair and the existing tool's {@code id}, so the mis-wiring is
 * diagnosable from the log without any secret or high-cardinality identifier.
 */
public class DuplicateToolRegistrationException extends IllegalStateException {

  /**
   * @param name the already-registered tool name
   * @param version the already-registered tool version
   * @param existingId the {@code id} of the descriptor already registered for that pair, for the
   *     diagnostic message
   */
  public DuplicateToolRegistrationException(String name, String version, String existingId) {
    super(
        "Duplicate tool registration for name='"
            + name
            + "', version='"
            + version
            + "' (existing tool id='"
            + existingId
            + "'). A tool is registered at most once per (name, version) and registration is "
            + "platform-only, at startup — never dynamic by the Planner or a user (S08-02).");
  }
}
