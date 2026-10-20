/**
 * <b>Owner:</b> {@code toolexecution}.
 *
 * <p>This is the public API boundary of the {@code toolexecution} bounded context — one of the
 * twelve bounded contexts of the AgentFlow modular monolith. Another module reaches it only through
 * the {@code application} services and the public {@code DTO}/{@code ID}/{@code enum} types listed
 * in {@code docs/architecture/module-api.md}; the {@code domain}, {@code persistence}, {@code web}
 * and {@code worker} sub-packages are internal and must not be imported by another module (enforced
 * by the architecture gate, {@code ./gradlew check}).
 */
package com.example.agentflow.toolexecution;
