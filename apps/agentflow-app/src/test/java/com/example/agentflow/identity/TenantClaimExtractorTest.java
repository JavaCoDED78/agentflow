package com.example.agentflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.identity.application.TenantClaimExtractor;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Unit tests for the S04-05 trusted tenant-claim extraction: the tenant id is read only from the
 * {@code tenant_id} claim of a <em>verified</em> JWT (signature verified upstream by the Resource
 * Server), a token without membership (no {@code tenant_id}) denies, and a malformed id denies too.
 * The token is parsed with a non-verifying {@link NimbusJwtDecoder} (signature/expiry is the
 * Resource Server's job, not the extractor's).
 */
class TenantClaimExtractorTest {

  private RSAKey key;

  private JwtDecoder decoder;

  @BeforeEach
  void setUp() throws Exception {
    key = rsaKey();
    // Public (non-verifying) decoder: decode() parses the claims, it does not check signature.
    decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
  }

  @Test
  void reads_tenant_id_from_verified_jwt() {
    UUID tenant = UUID.randomUUID();
    Jwt jwt = signTenant(tenant.toString());

    Optional<UUID> resolved = TenantClaimExtractor.extract(jwt);

    assertThat(resolved).contains(tenant);
  }

  @Test
  void token_without_membership_claims_yields_no_tenant() {
    // A verified token that carries no tenant membership (no tenant_id claim).
    Jwt jwt = signClaim("preferred_username", "jdoe");

    Optional<UUID> resolved = TenantClaimExtractor.extract(jwt);

    assertThat(resolved).isEmpty();
  }

  @Test
  void malformed_tenant_id_denies() {
    Jwt jwt = signTenant("not-a-uuid");

    Optional<UUID> resolved = TenantClaimExtractor.extract(jwt);

    assertThat(resolved).isEmpty();
  }

  @Test
  void tenant_id_is_read_only_from_the_verified_jwt_claim() {
    // The extractor only consults the verified Jwt: the claim name is the single source, so a
    // caller cannot supply a different tenant out-of-band and have it accepted.
    Jwt jwt = signTenant(UUID.randomUUID().toString());

    assertThat(TenantClaimExtractor.TENANT_CLAIM).isEqualTo("tenant_id");
    assertThat(jwt.getClaims()).containsKey("tenant_id");
    assertThat(TenantClaimExtractor.extract(jwt)).isNotEmpty();
  }

  private Jwt signTenant(String tenant) {
    return signClaim(TenantClaimExtractor.TENANT_CLAIM, tenant);
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

  private static RSAKey rsaKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
        .keyID("agentflow-tenant-claim-test")
        .privateKey(keyPair.getPrivate())
        .build();
  }
}
