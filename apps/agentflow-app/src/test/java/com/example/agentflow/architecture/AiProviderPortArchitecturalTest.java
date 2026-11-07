package com.example.agentflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * S09-01 architectural acceptance: the {@code ai} provider port contract is
 * <em>provider-agnostic</em>.
 *
 * <p>The whole point of the port is that the domain calls a neutral contract ({@link
 * com.example.agentflow.ai.application.ChatModelPort}, {@link
 * com.example.agentflow.ai.application.EmbeddingPort} and their request/response records) and never
 * a provider-specific DTO. The concrete provider adapter is added later (S09-02) and lives behind
 * the port; until then no provider SDK is on the classpath, so this test cannot merely be passing
 * vacuously — it imports the {@code ai} module and asserts that none of its classes *references* a
 * provider SDK package. It therefore guards the boundary against a future regression where a
 * provider class is imported into the contract (which would reintroduce the coupling the port is
 * meant to remove).
 */
class AiProviderPortArchitecturalTest {

  private static final String AI_MODULE = "com.example.agentflow.ai";

  // The neutral contract must never import the provider SDK or any provider's own client library.
  // Spring AI is the adapter we will add in S09-02; its provider-specific DTOs live under
  // org.springframework.ai and must stay behind the port, never in the contract.
  private static final String[] FORBIDDEN_PROVIDER_PACKAGES = {
    "org.springframework.ai", "com.openai", "com.anthropic", "com.google.ai", "cohere"
  };

  @Test
  void ai_provider_port_contract_depends_on_no_provider_sdk() {
    JavaClasses classes = new ClassFileImporter().importPackages(AI_MODULE);

    // A guard against a failed import passing the gate silently (mirrors the no-cycles gate).
    assertThat(classes).isNotEmpty();

    for (JavaClass self : classes) {
      for (Dependency dependency : self.getDirectDependenciesFromSelf()) {
        String target = dependency.getTargetClass().getPackageName();
        // Dependencies inside the module itself (the record on the interface) are fine.
        if (target.startsWith(AI_MODULE)) {
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
