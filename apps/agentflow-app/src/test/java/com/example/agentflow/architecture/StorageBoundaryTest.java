package com.example.agentflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.ArrayList;
import java.util.List;

/**
 * S02-18 architecture gate: the Object Storage boundary.
 *
 * <p>{@code storage} is the single owner of object bytes and of the provider-neutral {@code
 * ObjectStoragePort} contract ({@code docs/architecture/module-map.md}, arrows 22–23). Its Garage
 * adapter and the AWS SDK it is built on are <em>internal to the module</em>: no domain module may
 * import the adapter or the AWS SDK, and the public contract in the {@code storage} root package
 * stays provider-neutral (no references to the adapter or the SDK).
 *
 * <p>The forbidden target packages are matched with the {@code ..} (subpackage-including) pattern,
 * so a subpackage import such as {@code storage.adapter.SomeHelper} is caught — not just a direct
 * root-package import. Two independent rules:
 *
 * <ul>
 *   <li><strong>Contract purity</strong> — classes in the {@code storage} root package may not
 *       depend on the adapter or the AWS SDK (the contract is the public API boundary).
 *   <li><strong>Domain quarantine</strong> — classes in any module other than {@code storage} may
 *       not depend on the adapter or the AWS SDK (a domain module reaches storage through the
 *       contract, not through the implementation).
 * </ul>
 *
 * <p>{@code @AnalyzeClasses} is mandatory on every ArchUnit 1.5.x test class: without it the engine
 * refuses the class and silently runs zero tests (a false pass). The package is pinned explicitly —
 * with no {@code packages} argument the engine would import only the test class's own package, and
 * every rule below would pass vacuously against an empty set of analysed classes.
 */
@AnalyzeClasses(packages = "com.example.agentflow")
class StorageBoundaryTest {

  private static final String ROOT = "com.example.agentflow";

  /** Every application module except {@code storage} and its own internal packages. */
  private static final List<String> DOMAIN_MODULES =
      List.of(
          "identity",
          "agentcatalog",
          "workflowdefinition",
          "workflowexecution",
          "toolexecution",
          "knowledgebase",
          "ingestion",
          "ai",
          "usage",
          "audit",
          "messaging",
          "observability",
          "common");

  private static List<ArchRule> domainModuleRules() {
    List<ArchRule> rules = new ArrayList<>();
    for (String module : DOMAIN_MODULES) {
      rules.add(
          noClasses()
              .that()
              .resideInAPackage(ROOT + "." + module + "..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(ROOT + ".storage.adapter..", "software.amazon.awssdk..")
              .because(
                  "module "
                      + module
                      + " must reach Objects Storage through the public ObjectStoragePort contract "
                      + "in the storage root package — never the Garage adapter or the AWS SDK "
                      + "(docs/architecture/module-map.md, arrows 22–23)")
              .allowEmptyShould(true));
    }
    return rules;
  }

  /** The public contract stays provider-neutral; the adapter is a private implementation detail. */
  @ArchTest
  static final ArchRule storageContractStaysProviderNeutral =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".storage")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(ROOT + ".storage.adapter..", "software.amazon.awssdk..")
          .because(
              "the public contract in the storage root package is provider-neutral and must not "
                  + "depend on the Garage adapter or the AWS SDK")
          .allowEmptyShould(true);

  /** No domain module may import the adapter or the AWS SDK — through any subpackage. */
  @ArchTest
  static final ArchRule noDomainModuleImportsAdapterOrSdk =
      CompositeArchRule.of(domainModuleRules())
          .because(
              "docs/architecture/module-map.md: the Garage adapter and the AWS SDK are internal to "
                  + "the storage module; no domain module may import them (S02-18)")
          .allowEmptyShould(true);
}
