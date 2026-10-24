package com.example.agentflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.identity.application.ClaimsToAuthoritiesMapper;
import com.example.agentflow.identity.domain.Permission;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Unit tests for the S04-04 claims-to-authorities mapping: a verified JWT's role claims become
 * authorities only when the value is one of the catalogue ids, an unknown role claim grants nothing
 * (deny by default), and duplicate roles collapse to one authority. The token is parsed with a
 * non-verifying {@link NimbusJwtDecoder} (the signature/expiry checks are the Resource Server's
 * job, not this mapper's).
 */
class ClaimsToAuthoritiesMapperTest {

  private final ClaimsToAuthoritiesMapper mapper = new ClaimsToAuthoritiesMapper();

  private RSAKey key;

  private JwtDecoder decoder;

  @BeforeEach
  void setUp() throws Exception {
    key = RSAKey();
    // Public (non-verifying) decoder: decode() parses the claims, it does not check signature.
    decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
  }

  @Test
  void clientScopedRoleClaimsAreMappedToCatalogueAuthorities() {
    Jwt jwt =
        sign(Map.of("agentflow-service", List.of("workflow:read", "run:approve", "bogus:role")));

    Collection<GrantedAuthority> authorities = mapper.map(jwt);
    Set<String> ids = authorityIds(authorities);

    assertThat(ids).containsExactlyInAnyOrder("workflow:read", "run:approve");
    assertThat(ids).doesNotContain("bogus:role");
    assertEveryAuthorityIsCatalogueId(authorities);
  }

  @Test
  void realmScopedRoleClaimsAreMapped() {
    Jwt jwt = signClaim("realm-access", Map.of("roles", List.of("workflow:activate")));

    Collection<GrantedAuthority> authorities = mapper.map(jwt);

    assertThat(authorityIds(authorities)).containsExactly("workflow:activate");
    assertEveryAuthorityIsCatalogueId(authorities);
  }

  @Test
  void unknownRoleClaimsGrantNothing() {
    Jwt jwt = sign(Map.of("agentflow-service", List.of("bogus:role", "another-bogus:role")));

    Collection<GrantedAuthority> authorities = mapper.map(jwt);

    assertThat(authorities).isEmpty();
  }

  @Test
  void tokenWithoutRoleClaimsYieldsNoAuthorities() {
    Jwt jwt = signClaim("preferred_username", "jdoe");

    Collection<GrantedAuthority> authorities = mapper.map(jwt);

    assertThat(authorities).isEmpty();
  }

  @Test
  void duplicateRoleClaimsCollapseToOneAuthority() {
    Jwt jwt = sign(Map.of("client-a", List.of("workflow:read")));

    Jwt jwt2 = sign(Map.of("client-b", List.of("workflow:read")));

    Collection<GrantedAuthority> authorities =
        Stream.concat(mapper.map(jwt).stream(), mapper.map(jwt2).stream())
            .collect(Collectors.toSet());

    assertThat(authorityIds(authorities)).containsExactly("workflow:read");
  }

  private static Set<String> authorityIds(Collection<GrantedAuthority> authorities) {
    return authorities.stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
  }

  private static void assertEveryAuthorityIsCatalogueId(Collection<GrantedAuthority> authorities) {
    for (GrantedAuthority authority : authorities) {
      assertThat(Permission.fromId(authority.getAuthority()))
          .isPresent()
          .as("authority must map to a catalogue permission")
          .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(authority.getAuthority()));
    }
  }

  // --- test-key helpers (mirrors the production decoder setup) ---

  private Jwt sign(Map<String, List<String>> resourceAccess) {
    // Real Keycloak shapes each client's roles under a { roles: [...] } object:
    // resource_access = { "<client>": { roles: [...] } }.
    Map<String, Map<String, List<String>>> wrapped = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : resourceAccess.entrySet()) {
      wrapped.put(entry.getKey(), Map.of("roles", entry.getValue()));
    }
    return signClaim("resource_access", wrapped);
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

  private static RSAKey RSAKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
        .keyID("agentflow-mapper-test")
        .privateKey(keyPair.getPrivate())
        .build();
  }
}
