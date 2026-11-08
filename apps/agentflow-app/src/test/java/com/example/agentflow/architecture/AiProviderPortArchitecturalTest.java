package com.example.agentflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * S09-01 architectural acceptance: the {@code ai} provider <em>contract</em> (the {@code
 * application} package) is <em>provider-agnostic</em>.
 *
 * <p>The whole point of the port is that the domain calls a neutral contract ({@link
 * com.example.agentflow.ai.application.ChatModelPort}, {@link
 * com.example.agentflow.ai.application.EmbeddingPort} and their request/response records) and never
 * a provider-specific DTO. The concrete provider adapter (S09-02, the {@code provider} package) is
 * the only code that imports a provider SDK, and it lives <em>behind</em> the port — it is allowed
 * to import {@code org.springframework.ai}, because it is the adapter, not the contract.
 *
 * <p>The guard therefore scopes the assertion to the {@code application} package only: none of its
 * classes may reference a provider SDK package. That is the invariant the port exists to preserve,
 * and it is what keeps the security-critical layers decoupled from any specific provider. The
 * {@code provider} package is excluded on purpose — constraining it here would fail the gate as
 * soon as the adapter is added (S09-02) without guarding anything the port is actually about. The
 * guard {@code assertThat(classes).isNotEmpty()} prevents the gate from passing vacuously (it
 * mirrors the no-cycles gate).
 */
class AiProviderPortArchitecturalTest {

  // The contract package — the public API boundary the domain depends on. The adapter lives in the
  // sibling {@code provider} package and is allowed to import the provider SDK.
  private static final String CONTRACT_PACKAGE = "com.example.agentflow.ai.application";

  // The neutral contract must never import the provider SDK or any provider's own client library.
  // Spring AI is the adapter (S09-02); its provider-specific DTOs live under org.springframework.ai
  // and must stay behind the port, never in the contract.
  private static final String[] FORBIDDEN_PROVIDER_PACKAGES = {
    "org.springframework.ai", "com.openai", "com.anthropic", "com.google.ai", "cohere"
  };

  @Test
  void ai_provider_port_contract_depends_on_no_provider_sdk() {
    JavaClasses classes = new ClassFileImporter().importPackages(CONTRACT_PACKAGE);

    // A guard against a failed import passing the gate silently (mirrors the no-cycles gate).
    assertThat(classes).isNotEmpty();

    for (JavaClass self : classes) {
      for (Dependency dependency : self.getDirectDependenciesFromSelf()) {
        String target = dependency.getTargetClass().getPackageName();
        // Dependencies inside the contract package itself (the record on the interface) are fine.
        if (target.startsWith(CONTRACT_PACKAGE)) {
          continue;
        }
        assertThat(isProviderPackage(target))
            .as("the ai provider contract must be provider-agnostic but depends on %s", target)
            .isFalse();
      }
    }
  }

  private boolean isProviderPackage(String packageName) {
    for (String forbidden : FORBIDDEN_PROVIDER_PACKAGES) {
      if (packageName.startsWith(forbidden)) {
        return true;
      }
    }
    return false;
  }
}
