/**
 * <b>Owner:</b> {@code identity}.
 *
 * <p>Internal package: the pure domain model and invariants of the {@code identity} bounded context
 * (the {@code Permission} catalogue). This package is <em>internal</em> — another module must not
 * import it (enforced by the architecture gate, {@code ./gradlew check}). Use-cases in {@code
 * application} reach the model here through the module's public services.
 */
package com.example.agentflow.identity.domain;
