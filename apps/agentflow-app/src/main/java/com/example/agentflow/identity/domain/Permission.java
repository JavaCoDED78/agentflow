package com.example.agentflow.identity.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The AgentFlow permission catalogue (S04-04, {@code docs/security/permissions.md}).
 *
 * <p>The single source of truth for the {@code authorization} capability string a verified caller
 * must hold to perform an action. Each constant is one permission; its {@link #id()} is the literal
 * token shared by three consumers so they agree on what a permission <em>is</em>:
 *
 * <ul>
 *   <li>the <b>claims-to-authorities mapper</b> (S04-04) only ever grants an authority whose value
 *       matches one of these ids, so an unknown role claim cannot grant anything (deny by default);
 *   <li>the endpoint authorization matrix (S04-08) maps every {@code /api/v1} action to the id(s)
 *       it requires;
 *   <li>the Keycloak realm (S04-01) assigns these ids as client roles.
 * </ul>
 *
 * <p>The catalogue is intentionally closed: a role claim that is not one of these ids is ignored by
 * the mapper and denied by the endpoint matrix, so nobody can silently gain a capability by issuing
 * a token with an undocumented role. Extend it here (never with an ad-hoc string anywhere else)
 * when a new capability is needed.
 *
 * <p>The ids are the Sprint 4 canonical set from the plan's {@code S04-04} ({{@code workflow:read},
 * {@code workflow:write}, {@code workflow:activate}, {@code document:read}, {@code document:write},
 * {@code run:execute}, {@code run:approve}, {@code ops:dlq}}). The plan's illustrative {@code
 * §11.1} scope names ({@code workflow:approve}, {@code knowledge:read}, {@code tenant:admin}) are
 * superseded by these once the Keycloak realm converges on the catalogue (a later follow-up).
 */
public enum Permission {

  /** Read a workflow definition (list / get). */
  WORKFLOW_READ("workflow:read", "read workflow definitions"),

  /** Create or edit a workflow definition draft. */
  WORKFLOW_WRITE("workflow:write", "create and edit workflow definitions"),

  /** Activate an immutable workflow version. */
  WORKFLOW_ACTIVATE("workflow:activate", "activate an immutable workflow version"),

  /** Read a document or search the knowledge base (citations only from real chunks). */
  DOCUMENT_READ("document:read", "read documents and search the knowledge base"),

  /** Upload a document to the knowledge base. */
  DOCUMENT_WRITE("document:write", "upload documents"),

  /** Create a run, read run status/steps/result and cancel it. */
  RUN_EXECUTE("run:execute", "create runs, read run status and cancel"),

  /** Approve or reject a human-approval step. */
  RUN_APPROVE("run:approve", "approve or reject a human-approval step"),

  /** Inspect and reprocess dead-letter queue messages. */
  OPS_DLQ("ops:dlq", "inspect and reprocess dead-letter queue messages");

  private final String id;

  private final String description;

  private static final List<Permission> ALL = List.of(values());

  private static final Set<String> IDS =
      ALL.stream().map(Permission::id).collect(Collectors.toCollection(LinkedHashSet::new));

  private static final Map<String, Permission> BY_ID =
      ALL.stream().collect(Collectors.toMap(Permission::id, p -> p));

  Permission(String id, String description) {
    this.id = id;
    this.description = description;
  }

  /**
   * @return the canonical permission id (the token shared by the mapper, the endpoint matrix and
   *     the realm), e.g. {@code workflow:read}
   */
  public String id() {
    return id;
  }

  /**
   * @return a short, human-readable description of what the permission grants
   */
  public String description() {
    return description;
  }

  /**
   * @return every permission id in the catalogue, in declaration order; the single source of truth
   *     for the allowed authority values
   */
  public static Set<String> ids() {
    return Set.copyOf(IDS);
  }

  /**
   * @return the catalogue in declaration order; the reference list for the endpoint matrix and the
   *     Keycloak realm
   */
  public static List<Permission> all() {
    return ALL;
  }

  /**
   * Looks up a permission by its canonical id. Used by the claims-to-authorities mapper to validate
   * a role claim against the catalogue before granting it.
   *
   * @param value a permission id such as {@code workflow:read}
   * @return the matching permission, or {@link Optional#empty()} if the id is not in the catalogue
   */
  public static Optional<Permission> fromId(String value) {
    return Optional.ofNullable(BY_ID.get(value));
  }

  /**
   * @param value a permission id
   * @return {@code true} if the id is a member of the catalogue
   */
  public static boolean exists(String value) {
    return BY_ID.containsKey(value);
  }
}
