package com.example.agentflow.tooling;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/**
 * Entry point for the {@code ./gradlew flywayMigrate} task (S03-01).
 *
 * <p>Applies schema migrations with the Flyway Java API using the exact same Flyway version that is
 * on the application runtime classpath (BOM-managed), so the migration task and the running service
 * can never diverge on the Flyway line. The connection comes from the system properties set by the
 * Gradle task ({@code flyway.url}, {@code flyway.username}, {@code flyway.password}); the task
 * resolves them from the {@code FLYWAY_*} environment variables with the Compose {@code local}
 * profile as default, so the task runs against the local Postgres out of the box and the
 * application source stays free of any hardcoded connection.
 *
 * <p>Migrations run as one explicit step (the Gradle task locally, a controlled Helm Job in
 * Kubernetes), never concurrently from several replicas.
 *
 * <p>The default Flyway location is {@code classpath:migration} (singular); the AgentFlow
 * migrations live under {@code classpath:migrations} (plural), matching {@code
 * spring.flyway.locations} in {@code application.yml} and the {@code migrations/} folder, so the
 * location is set explicitly here or the {@code ./gradlew flywayMigrate} task would find zero
 * migrations.
 */
public final class FlywayMigrate {

  private FlywayMigrate() {}

  public static void main(String[] args) {
    String url = property("flyway.url");
    String username = property("flyway.username");
    String password = property("flyway.password");
    if (url == null || username == null || password == null) {
      System.err.println(
          "Missing migration connection. Set -Dflyway.url, -Dflyway.username and -Dflyway.password "
              + "(the flywayMigrate task provides these from the FLYWAY_* environment variables).");
      System.exit(2);
      return;
    }

    Flyway flyway;
    try {
      flyway =
          Flyway.configure()
              .dataSource(url, username, password)
              .locations("classpath:migrations")
              .load();
      flyway.migrate();
    } catch (FlywayException e) {
      System.err.println("Flyway migration failed: " + e.getMessage());
      System.exit(1);
      return;
    }
    System.out.println("Flyway migration applied successfully.");
  }

  private static String property(String name) {
    String value = System.getProperty(name);
    return (value == null || value.isBlank()) ? null : value;
  }
}
