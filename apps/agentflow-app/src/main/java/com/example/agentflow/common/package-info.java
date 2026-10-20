/**
 * <b>Owner:</b> {@code common}.
 *
 * <p>Shared, domain-neutral primitives: identifiers, an injectable {@code Clock}, the error
 * taxonomy and secret redaction. By rule this package holds <em>no</em> business logic and owns
 * <em>no</em> business table — it is not an escape route for a domain invariant, tenant check or
 * transaction. See "Cross-cutting packages" in {@code docs/architecture/module-map.md}.
 */
package com.example.agentflow.common;
