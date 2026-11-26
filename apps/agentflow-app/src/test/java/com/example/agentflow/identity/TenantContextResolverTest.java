package com.example.agentflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.identity.application.TenantClaimExtractor;
import com.example.agentflow.identity.application.TenantContext;
import com.example.agentflow.identity.application.TenantContextResolver;
import com.example.agentflow.identity.persistence.TenantRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the S04-06 {@link TenantContextResolver}: the tenant is resolved from the verified
 * JWT only, threaded as an immutable {@link TenantContext} together with the verified actor, denied
 * when there is no membership, and — the acceptance criterion — <b>parallel requests from different
 * tenants never mix context</b> because the resolver is stateless and stores nothing between calls.
 *
 * <p>The tokens are decoded with a non-verifying {@link NimbusJwtDecoder} (signature/expiry is the
 * Resource Server's job, S04-03); the resolver trusts whatever the verified token carries, exactly
 * as it would trust the {@link Jwt} Spring Security has already validated. The {@link
 * Authentication} instances below carry the {@link Jwt} (or a non-Jwt stub) as their principal,
 * exactly as the {@code SecurityContext} does for a bearer request.
 */
class TenantContextResolverTest {

  private RSAKey key;

  private JwtDecoder decoder;

  @BeforeEach
  void setUp() throws Exception {
    key = rsaKey();
    // Public (non-verifying) decoder: decode() parses the claims, it does not check signature.
    decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
  }

  /** Builds a resolver backed by the stub tenant repository mapping each tenant to its status. */
  private static TenantContextResolver resolver(Map<UUID, String> statuses) {
    return new TenantContextResolver(fakeRepo(statuses));
  }

  /**
   * A stub {@link TenantRepository}: status lookup by tenant id, {@code Optional.empty()} when the
   * id carries no row. Used as-is (never touching {@code JdbcTemplate}) so the application-layer
   * gate is exercised without a database.
   */
  private static TenantRepository fakeRepo(Map<UUID, String> statuses) {
    return new TenantRepository(null) {
      @Override
      public Optional<String> findStatus(UUID tenantId) {
        return Optional.ofNullable(statuses.get(tenantId));
      }
    };
  }

  @Test
  void resolves_tenant_and_verified_actor_from_verified_jwt() {
    UUID tenant = UUID.randomUUID();
    String actor = "jdoe";
    Jwt jwt = signWithTenantAndSubject(tenant.toString(), actor);

    TenantContext context = resolver(Map.of(tenant, "ACTIVE")).resolve(jwt);

    assertThat(context.tenantId()).isEqualTo(tenant);
    assertThat(context.actor()).isEqualTo(actor);
  }

  @Test
  void token_without_membership_claims_is_denied() {
    // A verified token that carries no tenant membership (no tenant_id claim).
    Jwt jwt = signClaim("preferred_username", "jdoe");

    assertThatThrownBy(() -> new TenantContextResolver(null).resolve(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void malformed_tenant_id_is_denied() {
    Jwt jwt = signWithTenantAndSubject("not-a-uuid", "jdoe");

    assertThatThrownBy(() -> new TenantContextResolver(null).resolve(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void resolves_from_the_security_context_principal() {
    UUID tenant = UUID.randomUUID();
    Jwt jwt = signWithTenantAndSubject(tenant.toString(), "jdoe");

    TenantContext context =
        resolver(Map.of(tenant, "ACTIVE")).resolveFromAuthentication(principal(jwt));

    assertThat(context.tenantId()).isEqualTo(tenant);
    assertThat(context.actor()).isEqualTo("jdoe");
  }

  @Test
  void principal_that_is_not_a_verified_jwt_is_denied() {
    // e.g. the permissive test principal on the {@code test} profile carries no verified Jwt.
    assertThatThrownBy(
            () -> new TenantContextResolver(null).resolveFromAuthentication(principal("anonymous")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void worker_resolves_tenant_from_the_stored_run() {
    // A worker re-checks the run's real tenant against PostgreSQL and passes it in; an
    // attacker-controlled Kafka header would never reach here.
    UUID tenant = UUID.randomUUID();

    TenantContext context =
        resolver(Map.of(tenant, "ACTIVE")).resolveFromStoredTenant(tenant, "agentflow-worker");

    assertThat(context.tenantId()).isEqualTo(tenant);
    assertThat(context.actor()).isEqualTo("agentflow-worker");
  }

  @Test
  void worker_resolves_tenant_from_stored_run_rejects_missing_tenant() {
    assertThatThrownBy(
            () -> new TenantContextResolver(null).resolveFromStoredTenant(null, "agentflow-worker"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  // --- S04-19: tenant existence & status verification ------------------------------

  @Test
  void unknown_disabled_and_no_membership_are_denied_identically() {
    // The S04-19 acceptance criterion: unknown and disabled tenants, and a user without
    // membership, all get the same denial — the answer never reveals whether the tenant exists
    // or its status.
    UUID unknown = UUID.randomUUID();
    UUID disabled = UUID.randomUUID();
    Jwt unknownJwt = signWithTenantAndSubject(unknown.toString(), "jdoe");
    Jwt disabledJwt = signWithTenantAndSubject(disabled.toString(), "jdoe");
    Jwt noMembershipJwt = signClaim("preferred_username", "jdoe");

    TenantContextResolver resolver = resolver(Map.of(disabled, "DISABLED"));

    ResponseStatusException unknownDenial = denialOf(() -> resolver.resolve(unknownJwt));
    ResponseStatusException disabledDenial = denialOf(() -> resolver.resolve(disabledJwt));
    ResponseStatusException noMembershipDenial =
        denialOf(() -> new TenantContextResolver(null).resolve(noMembershipJwt));

    for (ResponseStatusException denial :
        List.of(unknownDenial, disabledDenial, noMembershipDenial)) {
      assertThat(denial.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(denial.getReason()).isEqualTo(TenantContextResolver.NO_TENANT_MESSAGE);
    }
    assertThat(disabledDenial.getReason()).isEqualTo(unknownDenial.getReason());
    assertThat(noMembershipDenial.getReason()).isEqualTo(unknownDenial.getReason());
  }

  @Test
  void any_status_other_than_active_is_refused() {
    UUID tenant = UUID.randomUUID();
    Jwt jwt = signWithTenantAndSubject(tenant.toString(), "jdoe");

    for (String status : List.of("DISABLED", "SUSPENDED", "EMPTY", "")) {
      TenantContextResolver resolver = resolver(Map.of(tenant, status));
      assertThatThrownBy(() -> resolver.resolve(jwt))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              e ->
                  assertThat(((ResponseStatusException) e).getStatusCode())
                      .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
  }

  @Test
  void missing_database_is_denied_with_the_same_answer() {
    // The app boots without a database (S01-02); the tenant gate must fail closed, and — like every
    // other tenant failure — render the same tenant-agnostic answer.
    UUID tenant = UUID.randomUUID();
    Jwt jwt = signWithTenantAndSubject(tenant.toString(), "jdoe");

    ResponseStatusException denial = denialOf(() -> new TenantContextResolver(null).resolve(jwt));

    assertThat(denial.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(denial.getReason()).isEqualTo(TenantContextResolver.NO_TENANT_MESSAGE);
  }

  @Test
  void worker_refuses_an_unknown_tenant_and_a_disabled_one() {
    // The worker path (S04-06) is checked against the same gate — a stored run whose tenant
    // vanished or was disabled must not be processable.
    UUID unknown = UUID.randomUUID();
    UUID disabled = UUID.randomUUID();
    TenantContextResolver resolver = resolver(Map.of(disabled, "DISABLED"));

    assertThatThrownBy(() -> resolver.resolveFromStoredTenant(unknown, "agentflow-worker"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
    assertThatThrownBy(() -> resolver.resolveFromStoredTenant(disabled, "agentflow-worker"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  private static ResponseStatusException denialOf(Callable<TenantContext> call) {
    try {
      call.call();
    } catch (ResponseStatusException e) {
      return e;
    } catch (Exception e) {
      throw new IllegalStateException("expected a 401 denial, got " + e, e);
    }
    throw new IllegalStateException("expected a 401 denial, but resolution succeeded");
  }

  @Test
  void parallel_requests_from_different_tenants_do_not_mix_context() throws Exception {
    // The acceptance criterion for S04-06: the singleton resolver keeps no per-request state, so
    // many tenants resolved concurrently through the same instance cannot leak one tenant's
    // identity into another request's context.
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    Jwt jwtA = signWithTenantAndSubject(tenantA.toString(), "a");
    Jwt jwtB = signWithTenantAndSubject(tenantB.toString(), "b");
    TenantContextResolver shared = resolver(Map.of(tenantA, "ACTIVE", tenantB, "ACTIVE"));

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      Resolved a1 = new Resolved(shared, jwtA);
      Resolved b1 = new Resolved(shared, jwtB);
      Resolved a2 = new Resolved(shared, jwtA);
      Resolved b2 = new Resolved(shared, jwtB);
      List<TenantContext> resolved = runConcurrently(pool, List.of(a1, b1, a2, b2));

      assertThat(resolved).hasSize(4);
      assertThat(resolved.get(0).tenantId()).isEqualTo(tenantA);
      assertThat(resolved.get(1).tenantId()).isEqualTo(tenantB);
      assertThat(resolved.get(2).tenantId()).isEqualTo(tenantA);
      assertThat(resolved.get(3).tenantId()).isEqualTo(tenantB);
      assertThat(resolved.stream().map(TenantContext::actor)).containsExactly("a", "b", "a", "b");
    } finally {
      pool.shutdownNow();
    }
  }

  private static List<TenantContext> runConcurrently(ExecutorService pool, List<Resolved> tasks)
      throws InterruptedException, ExecutionException, TimeoutException {
    java.util.List<Future<TenantContext>> futures = new java.util.ArrayList<>();
    for (Resolved task : tasks) {
      futures.add(pool.submit(task));
    }
    java.util.List<TenantContext> results = new java.util.ArrayList<>();
    for (Future<TenantContext> future : futures) {
      results.add(future.get(5, TimeUnit.SECONDS));
    }
    return results;
  }

  private Jwt signWithTenantAndSubject(String tenant, String subject) {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .claim(TenantClaimExtractor.TENANT_CLAIM, tenant)
            .subject(subject)
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return decoder.decode(jwt.serialize());
  }

  private Jwt signClaim(String claim, Object value) {
    JWTClaimsSet claims = new JWTClaimsSet.Builder().claim(claim, value).build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return decoder.decode(jwt.serialize());
  }

  /** An {@link Authentication} whose principal is {@code principal}, like the SecurityContext. */
  private static Authentication principal(Object principal) {
    return new AbstractAuthenticationToken(List.of()) {
      @Override
      public Object getPrincipal() {
        return principal;
      }

      @Override
      public Object getCredentials() {
        return null;
      }
    };
  }

  private static RSAKey rsaKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
        .keyID("agentflow-tenant-context-test")
        .privateKey(keyPair.getPrivate())
        .build();
  }

  /** A concurrent resolve task carrying one {@link Jwt} and a shared resolver instance. */
  private final class Resolved implements Callable<TenantContext> {
    private final TenantContextResolver resolver;
    private final Jwt jwt;

    Resolved(TenantContextResolver resolver, Jwt jwt) {
      this.resolver = resolver;
      this.jwt = jwt;
    }

    @Override
    public TenantContext call() {
      return resolver.resolve(jwt);
    }
  }
}
