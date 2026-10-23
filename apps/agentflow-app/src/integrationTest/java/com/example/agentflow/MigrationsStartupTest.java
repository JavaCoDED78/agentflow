package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the application context boots against an <em>already</em> migrated schema (S03-15,
 * acceptance criterion 4), instead of applying migrations itself. It proves:
 *
 * <ul>
 *   <li>the container's schema is migrated once by this suite (not by the app); and
 *   <li>the Spring context loads and a real query against the migrated schema succeeds — the app
 *       starts cleanly on the schema the migrations produced.
 * </ul>
 *
 * <p>The production config keeps Flyway off; this test points a {@link DataSource} at the container
 * so the context can start against the pre-migrated schema. Spring Boot 4.1 no longer ships a JDBC
 * {@code DataSource} auto-configuration, so the test supplies the bean directly (guarded with
 * {@link ConditionalOnMissingBean} so a future auto-configuration does not clash).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(MigrationsStartupTest.DataSourceConfig.class)
class MigrationsStartupTest {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  /** Test-supplied {@link DataSource} pointed at the container. */
  @TestConfiguration
  static class DataSourceConfig {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    DataSource dataSource() {
      DriverManagerDataSource ds = new DriverManagerDataSource();
      ds.setUrl(postgres.getJdbcUrl());
      ds.setUsername(postgres.getUsername());
      ds.setPassword(postgres.getPassword());
      return ds;
    }
  }

  @Autowired ApplicationContext applicationContext;

  @BeforeAll
  static void migrate() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();
  }

  @Test
  void context_boots_on_the_migrated_schema() {
    // The very fact that the Spring context loaded (and the datasource bean is wired) proves the
    // application started against an already-migrated PostgreSQL schema.
    assertThat(applicationContext).isNotNull();
    JdbcTemplate jdbc = new JdbcTemplate(applicationContext.getBean(DataSource.class));
    // A live query proves the datasource reaches the migrated PostgreSQL and sees the tables.
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' "
                + "AND table_name IN ('tenants', 'workflow_definitions', 'workflow_definition_versions')",
            Integer.class);
    assertThat(count).isEqualTo(3);
  }
}
