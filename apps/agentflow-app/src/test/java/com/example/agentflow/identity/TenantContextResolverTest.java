package com.example.agentflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.identity.application.TenantClaimExtractor;
import com.example.agentflow.identity.application.TenantContext;
import com.example.agentflow.identity.application.TenantContextResolver;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
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

  private TenantContextResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    key = rsaKey();
    // Public (non-verifying) decoder: decode() parses the claims, it does not check signature.
    decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    // One singleton resolver instance shared by every test (and by the parallel test), so the
    // thread-safety claim is exercised against a real Spring-style singleton.
    resolver = new TenantContextResolver();
  }

  @Test
  void resolves_tenant_and_verified_actor_from_verified_jwt() {
    UUID tenant = UUID.randomUUID();
    String actor = "jdoe";
    Jwt jwt = signWithTenantAndSubject(tenant.toString(), actor);

    TenantContext context = resolver.resolve(jwt);

    assertThat(context.tenantId()).isEqualTo(tenant);
    assertThat(context.actor()).isEqualTo(actor);
  }

  @Test
  void token_without_membership_claims_is_denied() {
    // A verified token that carries no tenant membership (no tenant_id claim).
    Jwt jwt = signClaim("preferred_username", "jdoe");

    assertThatThrownBy(() -> resolver.resolve(jwt))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void malformed_tenant_id_is_denied() {
    Jwt jwt = signWithTenantAndSubject("not-a-uuid", "jdoe");

    assertThatThrownBy(() -> resolver.resolve(jwt))
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

    TenantContext context = resolver.resolveFromAuthentication(principal(jwt));

    assertThat(context.tenantId()).isEqualTo(tenant);
    assertThat(context.actor()).isEqualTo("jdoe");
  }

  @Test
  void principal_that_is_not_a_verified_jwt_is_denied() {
    // e.g. the permissive test principal on the {@code test} profile carries no verified Jwt.
    assertThatThrownBy(() -> resolver.resolveFromAuthentication(principal("anonymous")))
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

    TenantContext context = resolver.resolveFromStoredTenant(tenant, "agentflow-worker");

    assertThat(context.tenantId()).isEqualTo(tenant);
    assertThat(context.actor()).isEqualTo("agentflow-worker");
  }

  @Test
  void worker_resolves_tenant_from_stored_run_rejects_missing_tenant() {
    assertThatThrownBy(() -> resolver.resolveFromStoredTenant(null, "agentflow-worker"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
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

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      Resolved a1 = new Resolved(jwtA, tenantA);
      Resolved b1 = new Resolved(jwtB, tenantB);
      Resolved a2 = new Resolved(jwtA, tenantA);
      Resolved b2 = new Resolved(jwtB, tenantB);
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

  /** A concurrent resolve task carrying one {@link Jwt} and the tenant it must return. */
  private final class Resolved implements Callable<TenantContext> {
    private final Jwt jwt;
    private final UUID expectedTenant;

    Resolved(Jwt jwt, UUID expectedTenant) {
      this.jwt = jwt;
      this.expectedTenant = expectedTenant;
    }

    @Override
    public TenantContext call() {
      return resolver.resolve(jwt);
    }

    @Override
    public String toString() {
      return "Resolved{tenant=" + expectedTenant + "}";
    }
  }
}
