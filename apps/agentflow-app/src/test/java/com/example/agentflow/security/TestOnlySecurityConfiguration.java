package com.example.agentflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-only permissive security chain (S04-10).
 *
 * <p>The permissive {@link SecurityFilterChain} — the temporary local-auth bypass that lets the
 * many controller tests exercise endpoints without a verified JWT — lives in the <em>test</em>
 * source set, never in the production artifact. It is active only on the {@code test} profile:
 * unit/integration suites that run controllers without authenticating activate it via
 * {@code @ActiveProfiles("test")}, and on that profile the <em>secured</em> chain
 * ({@code @Profile("!test")}) is absent, so exactly one chain is registered.
 *
 * <p>Keeping the bypass out of {@code src/main} is the S04-10 fix: the permissive chain can no
 * longer ship in the production jar, so even a misconfigured deployment that activates the {@code
 * test} profile cannot open the API — the secured chain is the only {@link SecurityFilterChain} on
 * the classpath. It never runs on the {@code kubernetes} or {@code production} profile.
 */
@Configuration
@Profile("test")
public class TestOnlySecurityConfiguration {

  @Bean
  SecurityFilterChain permissiveSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
