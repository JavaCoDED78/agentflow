package com.example.agentflow.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read path for the {@code tenants} table (S03-03), the single row-by-id existence/status probe the
 * {@code identity} module owns.
 *
 * <p>This repository is deliberately read-write, not read-only, only in the sense that the S04-19
 * contract needs a single lookup; the {@code tenants} table is administrator-managed ({@code
 * status} is an administrator-only lifecycle field, S03-03), so the resolver never writes here. It
 * exists so the tenant resolver can answer, in one round trip and with the SQL predicate in the
 * query itself: <i>does this tenant exist, and with what status?</i>
 *
 * <p>The bean is only registered when a {@link DataSource} exists ({@code @ConditionalOnBean(
 * DataSource.class)}), mirroring the other owning-module repositories, so the application still
 * boots without a database (S01-02); the resolver fails closed when this repository is absent.
 *
 * <p>{@code identity} is the only module that reads or writes the {@code tenants} table ({@code
 * docs/architecture/module-map.md}); no other module imports this class.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class TenantRepository {

  private static final String TABLE = "tenants";

  private final JdbcTemplate jdbc;

  public TenantRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Read the raw {@code status} of the tenant, or {@link Optional#empty()} when the tenant does not
   * exist. The raw value is returned unvalidated on purpose: the policy that only a particular
   * status is allowed ({@code ACTIVE}) lives in the resolver's tenant-resolution contract, not in
   * the storage layer.
   *
   * @param tenantId the owning tenant
   * @return the tenant's {@code status} (e.g. {@code ACTIVE}), or {@link Optional#empty()} when no
   *     row exists for that id
   */
  public Optional<String> findStatus(UUID tenantId) {
    List<String> statuses =
        jdbc.query(
            "SELECT status FROM " + TABLE + " WHERE id = ?",
            (rs, rowNum) -> rs.getString("status"),
            tenantId);
    return statuses.stream().findFirst();
  }
}
