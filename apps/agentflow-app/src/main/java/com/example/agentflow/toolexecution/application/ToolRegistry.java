package com.example.agentflow.toolexecution.application;

import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The in-memory, deterministic registry of {@link ToolDescriptor}s (S08-02).
 *
 * <p>It is the single source of <em>which tools are registered</em>: the platform registers the
 * built-in tool descriptors once at startup, and every later lookup — the Planner choosing a tool,
 * the permission check reading a tool's risk class and required scopes — goes through this
 * registry. It has exactly three responsibilities, all of which close the S08-02 acceptance
 * criteria:
 *
 * <ul>
 *   <li><b>Register at startup.</b> {@link #register(ToolDescriptor)} adds a descriptor. It is a
 *       platform operation called only from the {@code toolRegistry} bean (see {@code
 *       ToolRegistryConfiguration}); there is <em>no</em> HTTP endpoint or API that a Planner or a
 *       user can call to register a tool, so dynamic registration by the model/user is impossible
 *       (an invariant, {@code AGENTS.md} "Forbidden").
 *   <li><b>Deduplicate on {@code (name, version)}.</b> {@link #register(ToolDescriptor)} rejects a
 *       second registration of a pair already present with a {@link
 *       DuplicateToolRegistrationException} (a startup error that breaks context start).
 *   <li><b>Resolve by {@code (name, version)}.</b> {@link #resolve(String, String)} returns the
 *       registered descriptor, or {@link Optional#empty()} for an unknown pair — so an unknown tool
 *       resolves to nothing and is therefore never invoked (S08-02 acceptance: "unknown tool never
 *       called"). Resolution is version-strict: a pair that was never registered is unknown, there
 *       is no implicit "latest".
 * </ul>
 *
 * <p>It is a thread-safe singleton: {@link #register} is called once during context startup and
 * {@link #resolve} is called concurrently by the worker that executes a run, so the backing map is
 * a {@link ConcurrentMap} and registration is atomic ({@link ConcurrentMap#putIfAbsent}), which
 * also makes a concurrent duplicate detectable rather than silently lost. It holds <em>only
 * descriptors</em> (metadata: schema, risk class, scopes, timeout, retry policy) — the tool
 * <em>executors</em> are registered separately by name, so the registry never executes anything
 * itself.
 *
 * <p>The registration key is the {@code (name, version)} pair, not the descriptor {@code id}: the
 * Planner references a tool by {@link ToolDescriptor#name()} and a lookup by name must resolve to
 * exactly one tool, so {@code (name, version)} is the unique key. The {@code id} stays on the
 * descriptor as a stable, unique identifier for audit/persistence.
 */
public class ToolRegistry {

  private final ConcurrentMap<ToolKey, ToolDescriptor> tools = new ConcurrentHashMap<>();

  /**
   * Registers a tool descriptor. The descriptor is validated when it is built (the {@code
   * ToolDescriptor} canonical-constructor rejects an invalid one), so this guards the registration
   * contract instead: the pair must be non-null, and the pair must not already be registered.
   *
   * @param descriptor the descriptor to register; never {@code null}
   * @throws NullPointerException if {@code descriptor} is {@code null}
   * @throws DuplicateToolRegistrationException if a descriptor for the same {@code (name, version)}
   *     pair is already registered
   */
  public void register(ToolDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor must not be null");
    ToolKey key = new ToolKey(descriptor.name(), descriptor.version());
    ToolDescriptor existing = tools.putIfAbsent(key, descriptor);
    if (existing != null) {
      throw new DuplicateToolRegistrationException(key.name(), key.version(), existing.id());
    }
  }

  /**
   * Resolves the descriptor registered for a {@code (name, version)} pair.
   *
   * @param name the callable tool name to look up
   * @param version the version to look up
   * @return the registered descriptor, or {@link Optional#empty()} if nothing is registered for the
   *     pair — so an unknown tool resolves to nothing and is never invoked
   */
  public Optional<ToolDescriptor> resolve(String name, String version) {
    return Optional.ofNullable(tools.get(new ToolKey(name, version)));
  }

  /**
   * @param name the callable tool name
   * @param version the version
   * @return {@code true} if a descriptor is registered for the {@code (name, version)} pair
   */
  public boolean containsTool(String name, String version) {
    return tools.containsKey(new ToolKey(name, version));
  }

  /**
   * @return an unmodifiable snapshot of every registered descriptor (a copy, so later registration
   *     or mutation cannot change a caller's view of the registry)
   */
  public Set<ToolDescriptor> all() {
    return Set.copyOf(tools.values());
  }

  /**
   * @return the number of registered tools
   */
  public int size() {
    return tools.size();
  }
}
