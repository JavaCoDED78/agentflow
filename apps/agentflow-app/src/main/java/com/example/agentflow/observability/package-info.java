/**
 * <b>Owner:</b> {@code observability}.
 *
 * <p>Cross-cutting telemetry: structured JSON logging with secret masking, domain meters, trace and
 * correlation attributes. It offers helpers consumed by every module; it is <em>not</em> a domain
 * module. Consolidates the S01-19 structured-logging code that previously lived in the {@code
 * logging} package. See "Cross-cutting packages" in {@code docs/architecture/module-map.md}.
 */
package com.example.agentflow.observability;
