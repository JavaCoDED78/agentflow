package com.example.agentflow.agentcatalog.domain;

/**
 * The risk class (blast radius) of a registered tool (glossary "Tool risk class"; {@code
 * tool_definitions.risk_class}).
 *
 * <p>It answers the question <em>what kind of side effect can this tool cause?</em>, which the
 * permission check combines with the caller's scopes to decide whether a call is allowed. The set
 * is intentionally closed: an unknown risk class is denied by default (see {@code S11-01}) — a
 * classification that does not resolve to one of the values below is rejected before the tool is
 * registered or called, so the enum itself can never carry an unclassified value.
 *
 * <p>The values are ordered by increasing blast radius (used only to compare how much trust a tool
 * needs, never to expand the allowlist):
 *
 * <ul>
 *   <li>{@link #READ_ONLY} — observes data only; safe to repeat (e.g. {@code calculator.evaluate},
 *       {@code report.format}).
 *   <li>{@link #WRITE} — changes tenant-scoped state in the platform (e.g. persisting a result).
 *   <li>{@link #EXTERNAL_SIDE_EFFECT} — acts on the outside world in a way the platform cannot undo
 *       (e.g. sending an email or a payment); such a call is <em>not</em> exactly-once (invariant
 *       6) and is retried only when the tool is idempotent.
 * </ul>
 *
 * <p>Owner: {@code agentcatalog} (the tool allowlist and risk-class vocabulary live here, {@code
 * module-map.md} edge 3), so the {@code toolexecution} tool registry can reference the type through
 * that allowed edge without the reverse (catalogue &rarr; registry) dependency that would be
 * forbidden.
 */
public enum ToolRiskClass {
  READ_ONLY,
  WRITE,
  EXTERNAL_SIDE_EFFECT
}
