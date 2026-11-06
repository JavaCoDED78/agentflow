package com.example.agentflow.toolexecution.application;

/**
 * The registration/resolution key of a {@link ToolRegistry} entry: the tool {@code name} the
 * Planner references, plus the {@code version} it was registered under.
 *
 * <p>The registry deduplicates on this pair (S08-02). {@code name} is the callable name the Planner
 * uses to refer to the tool (see {@code ToolDescriptor#name()}) and {@code version} is the
 * descriptor version; together they uniquely identify a registered tool version (S08-01: "the pair
 * (name, version) identifies a tool version"). Because the Planner resolves a tool by name, the
 * pair must be a unique key so that a lookup can never silently resolve to the wrong tool — which
 * is what makes the "unknown tool is never called" guarantee (S08-02 acceptance) sound.
 *
 * <p>It is a plain value (record) with value equality, so it is a safe map key. It stays
 * package-private: the registry resolves by {@code (name, version)} strings, so the key never
 * escapes the {@code application} package and is not part of the module's public surface.
 *
 * @param name the callable tool name (never {@code null} or blank for a valid descriptor)
 * @param version the descriptor version (never {@code null} or blank for a valid descriptor)
 */
record ToolKey(String name, String version) {}
