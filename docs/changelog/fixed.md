# Changelog



# Archived — Fixed (≤ 0.9.3)



## S04-14 — `bootRun` resolves `runtimeClasspath` (dependency-lock gap closed, S04-14 follow-up).



- **`bootRun` resolves `runtimeClasspath` (dependency-lock gap closed, S04-14 follow-up).** The
  `apps/agentflow-app/gradle.lockfile` pinned the security stack (`nimbus-jose-jwt`, the
  `spring-security-*` suite and the `spring-boot-security` /
  `spring-boot-starter-oauth2-resource-server` starters) only for `compileClasspath` /
  `testCompileClasspath` / `testRuntimeClasspath`. Because the app's lock resolution hard-fails
  when a resolved version is missing from the lock, `./gradlew bootRun` aborted with
  `"not part of the dependency lock state"` even though the build and the `check` gate passed —
  `check` never resolves `runtimeClasspath`, so the gap was invisible there. Regenerated the lock
  with `./gradlew generateLockFiles --write-locks`; the diff adds `runtimeClasspath` to exactly
  those 12 entries and is idempotent (a second regeneration produces no diff). Verified: the app
  boots with `-Dspring.profiles.active=local`, `Tomcat started on port 8080`, secured endpoint
  returns `401` without a token and `403` with a valid `agentflow-service` m2m token (passes the
  Resource Server signature/issuer/audience checks, denied by the AccessMatrix for the missing
  `workflow:read` role) — matching the runbook's documented results.

- **Removed the deprecated {@code JsonNode.isTextual()} API (`workflowdefinition`).** {@code WorkflowGraphParser} asserted that the {@code schemaVersion} field and each per-entity {@code <field>} JSON node is a string via {@code JsonNode.isTextual()}; that method is deprecated since Jackson 3.0 (its Javadoc points at {@code isString()}), so {@code :apps:agentflow-app:compileJava} emitted a {@code [deprecation]} warning. Both occurrences were replaced with {@code isString()}. Verified: {@code ./gradlew :apps:agentflow-app:compileJava} no longer emits the {@code [deprecation]} warning and {@code ./gradlew :apps:agentflow-app:check} is green (parser tests, the architecture gates, the OpenAPI contract gate, and {@code spotless}); no behavior change — {@code isString()} and the removed {@code isTextual()} both mean {@code getNodeType() == JsonNodeType.STRING}.

- **`RunService.getRun` no longer throws {@code ClassCastException} mapping {@code created_at} to {@code OffsetDateTime}.** {@code WorkflowRunRepository.findRun} cast the JDBC {@code created_at} column — which the {@code pgvector} / PostgreSQL driver returns as a {@code java.sql.Timestamp} — directly to {@code java.time.OffsetDateTime}, so the call failed at runtime the moment a real {@code DataSource} was present, even though the unit tests (which mock the repository) passed. That same method backs both the new {@code GET /api/v1/runs/{runId}} endpoint ({@link RunService#getRun}) and the worker's {@code findRun} calls, so this was a fault of the shared read path, not one endpoint. Replaced the cast with the typed JDBC call {@code rs.getObject("created_at", OffsetDateTime.class)}, mirroring the sibling mapper that already does it correctly, so the timestamp is converted rather than cast. Verified: {@code ./gradlew :apps:agentflow-app:test} is green (unit + architecture + formatting) and {@code ./gradlew integrationTest} is green — {@code WorkflowWorkerEndToEndIT} now reaches {@code SUCCEEDED} through the real state machine over real PostgreSQL.




### Fixed



## S04-03 — `jwtDecoder` no longer uses the deprecated Nimbus `RemoteJWKSet` (S04-03).



- **`jwtDecoder` no longer uses the deprecated Nimbus `RemoteJWKSet` (S04-03).** The
  original S04-03 implementation built the standard `NimbusJwtDecoder` with the deprecated
  Nimbus `RemoteJWKSet` (class-level `@Deprecated`), which triggered `[deprecation]`
  warnings under `-Xlint:deprecation` and was scheduled for removal. The decoder is now
  built with the non-deprecated `NimbusJwtDecoder.withJwkSource(...)` API: a small
  `JWKSource<SecurityContext>` resolves the issuer's OIDC discovery document and fetches
  `jwk_set_uri` only on the first `select(...)` (never at startup), so the context still
  boots without a reachable Keycloak — the lazy-OIDC property is preserved — and the bean
  is still a `NimbusJwtDecoder`. `RemoteJWKSet` is gone from the codebase (zero
  `[deprecation]` warnings remain from this file). Verification: `./gradlew :apps:agentflow-app:check` is green
  (unit + architecture gates + formatting + OpenAPI); `RemoteJWKSet` produces no
  deprecation warnings; and the context-startup tests on the `default`/`kubernetes`/`local`
  profiles (which boot without Keycloak) pass — `productionJwtDecoderBuildsWithLocalIssuer`
  additionally asserts the production bean is a `NimbusJwtDecoder`. The `JsonNode.asText()`
  deprecations noted here were removed later in the S04-03 follow-up (see the entry above). The
  key lookup itself (JWK set from `jwk_set_uri`, with a `jwks_uri` fallback for Keycloak) is a
  later fix — see "JWT decoder resolves the JWK set via `jwks_uri`" below.
- **JWT decoder resolves the JWK set via `jwks_uri` when discovery omits `jwk_set_uri`.** The
  `default`/`local`/`kubernetes` secured chain builds the `NimbusJwtDecoder` JWK source from the
  issuer's OIDC discovery document (`SecurityConfiguration.issuerJwkSource` →
  `jwkSetFromIssuer`), reading the JWK set URL from the `jwk_set_uri` field. Keycloak 26.8.1 —
  the realm used locally and Keycloak's own `master` realm — exposes the JWK set under the legacy
  `jwks_uri` discovery field and not the OIDC-standard `jwk_set_uri`, so `getURI(metadata,
  "jwk_set_uri")` returned `null` and the decoder rejected every token with `401 invalid_token`
  *before* the issuer and audience checks ever ran. That is what produced the opaque local `401`
  on `/api/v1` despite a well-formed `aud` claim: the failure was the key-resolution naming gap,
  not the audience. The decoder now reads `jwk_set_uri` first and falls back to `jwks_uri` (both
  point at the same `/protocol/openid-connect/certs` endpoint) so an issuer is never rejected for a
  field-name difference; if discovery exposes neither, resolution fails with a clear message. No
  validation is weakened — the signature, issuer, audience and expiry checks are unchanged, only
  the key lookup is now tolerant of both conventions. Verified locally: the secured chain on the
  `default` profile now verifies a real `agentflow-service` Client Credentials token (whose
  `aud=agentflow-api`) past the security layer to a `403` (missing `workflow:read`), and still
  rejects an unauthenticated request with `401`. The `jwks_uri` fallback is a regression unit test
  (`SecurityConfigurationJwkResolutionTest`), which drives `jwkSetFromIssuer` against a local HTTP
  server covering the `jwk_set_uri` path, the `jwks_uri` fallback, and the neither-present case.
- **No deprecated `JsonNode.asText()` in the build (S04-03 follow-up).** The standard
  Jackson 3.1.5 `JsonNode.asText()` is a deprecated alias — its Javadoc says `@deprecated
  Use asString() instead`, and `asText()` is implemented as `return asString()` — which left
  18 `[deprecation]` warnings under `-Xlint:deprecation`: 13 in `GlobalExceptionHandlerTest`
  and 5 in `OpenApiCompatibility`. All calls were replaced with the non-deprecated
  `asString()` (the method `asText()` already delegates to), so there is no behavior change:
  the RFC 9457 envelope assertions and the breaking/compatible classification read the same
  string values. Verification: a fresh `compileJava`/`compileTestJava` under `-Xlint:deprecation`
  reports zero `[deprecation]` warnings, `GlobalExceptionHandlerTest` passes 5/5, and
  `OpenApiContractCheckTest` passes — no semantic change to either.



## S01-17 — Typed config binds under the default profile (`S01-17`).



- **Typed config binds under the default profile (`S01-17`).** The `agentflow.app.*`
  configuration was declared with a nested `agentflow.app.name` key, but the Java
  model is `@ConfigurationProperties(prefix = "agentflow")` with a flat `appName`
  field, which binds from `agentflow.app-name` / `agentflow.appName` only. The nested
  key never bound, so `app.name` came back as `null` under the default profile and the
  Spring context failed to start. All `application*.yml` profiles now use the flat
  `agentflow.app-name` key; the `@NotBlank` validation message and Javadoc were
  corrected to match. The startup-failure test now asserts on the real
  `ConfigurationPropertiesBindException` reported by `ApplicationContextRunner` (a
  missing required value fails context startup via `@Validated` binding), and
  `./gradlew clean check` is green.



## S01-07 — `apps/agentflow-app` test task runs (S01-07).



- **`apps/agentflow-app` test task runs (S01-07).** The Boot 4.1.1 BOM manages JUnit
  to the 6.0.x line and `spring-boot-test` is compiled against the JUnit Platform
  6.0 `Store` API, so forcing the JUnit 5 line broke Spring context startup
  (`NoSuchMethodError`) and the platform discovered zero tests. We keep the
  BOM-managed JUnit 6.0.3 and add `junit-platform-launcher` (also BOM-managed) to
  the test runtime classpath. The `test` task now compiles and runs the
  `AgentflowApplicationTests` context-load test. `failOnNoDiscoveredTests = true`
  makes a future zero-test discovery fail the build instead of passing silently.

### Security
