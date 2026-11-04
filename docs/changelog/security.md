# Changelog



# Archived — Security (≤ 0.9.3)



## S04-10 — Local auth bypass removed (S04-10).



- **Local auth bypass removed (S04-10).** The permissive {@code test}-profile
  {@code SecurityFilterChain} — the temporary local mock-auth that let the many controller tests
  run without a verified JWT — is no longer part of the production artifact. It moved to a
  test-only config
  ({@code apps/agentflow-app/src/test/java/com/example/agentflow/security/TestOnlySecurityConfiguration.java},
  {@code @Profile("test")}), so the permissive chain can never ship in the jar. On the {@code test}
  profile the secured chain ({@code @Profile("!test")}) is absent, so exactly one chain is
  registered; on every other profile the secured chain is the only one. Even a misconfigured
  deployment that activates the {@code test} profile can no longer open the API: the
  production/kubernetes profile never starts with a permissive principal (the S04-10 acceptance
  criterion). The {@code jwtDecoder} / {@code jwtAuthenticationConverter} beans and the {@code
  secured-test} profile (the in-memory-key secured chain) are unchanged; {@code ./gradlew
  :apps:agentflow-app:test} is green — the {@code test}-profile suites ({@code
  ConfigurationTestProfileTest}, {@code HealthEndpointsTest}, {@code
  GlobalExceptionHandlerTest}) still boot the permissive chain, and {@code
  SecurityFilterChainTest} still runs the secured chain on {@code secured-test}. The
  {@code ${agentflow.local.tenant-id}} local default (S03-13/S03-14) is a *separate*
  tenant-isolation bypass that this sprint deliberately did not remove (threading the tenant from
  the verified context needs a new `workflowdefinition` → `identity` edge, an ADR) and is tracked
  as a follow-up.
- Security-relevant changes (for example the repo
  secret hygiene, `S01-11`) are recorded under `Added`; dedicated security
  entries use this section going forward (see `CONTRIBUTING.md`).
