package com.example.agentflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Architecture boundary gate (S02-08).
 *
 * <p>Enforces, at {@code ./gradlew check} time, the compile-time module boundaries of the modular
 * monolith declared in {@code docs/architecture/module-map.md} (the dependency table: arrows 1-13
 * plus the execution {@code StepHandler} SPI arrows 14-16 and the storage edges 22-23). For every
 * module the rule forbids a compile dependency to any other module that is <em>not</em> in that
 * module's allowed column. External dependencies (Spring, Jackson, jakarta, the database, Kafka,
 * ...) are not module edges and therefore are not forbidden, so they never trip the gate.
 *
 * <p>Each module contributes one sub-rule; they are combined into a single {@code @ArchTest} so one
 * introduced violation anywhere fails the build. This is the S02-08 acceptance criterion.
 *
 * <p>Why ArchUnit and not Spring Modulith: Spring Modulith is not managed by the Spring Boot 4.1.1
 * BOM (there is no {@code spring.modulith.version} property in {@code spring-boot-dependencies}),
 * so pulling it in would require pinning a version outside the BOM. ArchUnit is a third-party
 * testing library, pinned in the version catalog like every other dependency, so it does not bypass
 * the BOM and keeps {@code ./gradlew check} green.
 *
 * <p>Note: the forbidden set is derived as {@code all modules - self - allowed}, so it is complete
 * for the modules named in the map. Adding a module (which an ADR requires per {@code AGENTS.md})
 * means adding it to {@link #MODULES} and its allowed column; until then a compile edge to an
 * unknown package is not caught by this gate.
 *
 * <p>Subpackage-aware: both the analysed and the forbidden sides are matched with the {@code ..}
 * (subpackage-including) pattern, so a subpackage class such as {@code knowledgebase.persistence}
 * importing a forbidden module fails the gate. Test classes are excluded ({@code
 * ImportOption.DoNotIncludeTests}): this gate pins the compile-time boundaries of the production
 * wiring, not of the test tree.
 *
 * <p>{@code @AnalyzeClasses} is mandatory on every ArchUnit 1.5.x test class: without it the engine
 * refuses the class and silently runs zero tests (a false pass), which is why the absence of this
 * annotation must never be removed.
 */
@AnalyzeClasses(
    packages = "com.example.agentflow",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureBoundaryTest {

  private static final String ROOT = "com.example.agentflow";

  private static final List<String> MODULES =
      List.of(
          "identity",
          "agentcatalog",
          "workflowdefinition",
          "workflowexecution",
          "toolexecution",
          "knowledgebase",
          "ingestion",
          "storage",
          "ai",
          "usage",
          "audit",
          "messaging",
          "observability",
          "common");

  /** Allowed compile-time targets per module (module-map.md arrows 1-19, self included). */
  private static final Map<String, Set<String>> ALLOWED =
      Map.ofEntries(
          Map.entry("identity", Set.of("identity")),
          Map.entry("agentcatalog", Set.of("agentcatalog", "identity")),
          Map.entry("workflowdefinition", Set.of("workflowdefinition", "agentcatalog", "audit")),
          Map.entry(
              "workflowexecution",
              Set.of(
                  "workflowexecution",
                  "workflowdefinition",
                  "messaging",
                  "audit",
                  "common",
                  "storage")),
          Map.entry(
              "toolexecution",
              Set.of("toolexecution", "agentcatalog", "audit", "workflowexecution")),
          Map.entry(
              "knowledgebase",
              Set.of("knowledgebase", "ai", "workflowexecution", "messaging", "storage")),
          Map.entry("ingestion", Set.of("ingestion", "knowledgebase", "messaging", "ai")),
          // S02-18: storage is the sole owner of object bytes; only knowledgebase (documents) and
          // workflowexecution (large-payload checkpoints) may compile-depend on it (arrows 22–23).
          Map.entry("storage", Set.of("storage")),
          Map.entry("ai", Set.of("ai", "toolexecution", "workflowexecution")),
          Map.entry("usage", Set.of("usage", "ai", "workflowexecution")),
          Map.entry("audit", Set.of("audit", "identity", "common")),
          // S12-11: the DLQ inspection audit writes through `audit.application.AuditService`, so
          // `messaging` compiles-depend on `audit` (arrow 21, ADR-022).
          Map.entry("messaging", Set.of("messaging", "common", "audit")),
          Map.entry("observability", Set.of("observability", "common")),
          Map.entry("common", Set.of("common")));

  private static List<ArchRule> buildBoundaryRules() {
    List<ArchRule> rules = new ArrayList<>();
    for (String module : MODULES) {
      Set<String> allowed = ALLOWED.get(module);
      String[] forbidden =
          MODULES.stream()
              .filter(other -> !other.equals(module) && !allowed.contains(other))
              .map(other -> ROOT + "." + other + "..")
              .toArray(String[]::new);
      rules.add(
          noClasses()
              .that()
              .resideInAPackage(ROOT + "." + module + "..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(forbidden)
              .because(
                  "module "
                      + module
                      + " may only compile-depend on its own package plus "
                      + allowed.stream().filter(m -> !m.equals(module)).toList())
              .allowEmptyShould(true));
    }
    return rules;
  }

  @ArchTest
  static final ArchRule moduleBoundaries =
      CompositeArchRule.of(buildBoundaryRules())
          .because(
              "docs/architecture/module-map.md dependency table (compile edges 1-13, 18, 19, 22-23)")
          .allowEmptyShould(true);
}
