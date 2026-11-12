package com.example.agentflow.knowledgebase.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read path for a {@code knowledge_bases} row, owned by the {@code knowledgebase} module.
 *
 * <p>The {@code knowledge_bases} table is owned by this module (S10-05); until the knowledge-base
 * management API lands, the retrieval service is its first read-only consumer. Backed by a plain
 * {@link JdbcTemplate} (Spring Data JDBC is the single persistence approach, {@code ADR-009}); the
 * bean is registered only when a {@link DataSource} exists ({@code @ConditionalOnBean(DataSource
 * .class)}), so the application boots without a database ({@code S01-02}) and the caller fails
 * loudly when the repository is absent — the sibling-repository idiom.
 *
 * <p>Every read is scoped by the {@code (tenant_id, id)} pair (invariant 1): another tenant's
 * knowledge base is indistinguishable from a missing one, so a cross-tenant id yields {@link
 * Optional#empty()} — a retrieval against it simply finds nothing, exactly like a cross-tenant
 * lookup of any other resource.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class KnowledgeBaseRepository {

  private static final String TABLE = "knowledge_bases";

  private final JdbcTemplate jdbc;

  /**
   * @param jdbc the JDBC template for the application data source
   */
  public KnowledgeBaseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Read the embedding model pinned on one tenant-owned knowledge base (S10-05). The tenant
   * predicate is part of the query itself (invariant 1); a forged or cross-tenant id yields {@link
   * Optional#empty()}.
   *
   * <p>The retrieval service embeds the query text with exactly this model: the query vector must
   * live in the same space as the stored {@code vector(768)} chunks, and the collection row is the
   * source of truth for which model that is.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param knowledgeBaseId the knowledge base id
   * @return the pinned {@code embedding_model}, or empty when no such tenant-owned knowledge base
   *     exists
   */
  public Optional<String> findEmbeddingModelByIdAndTenant(UUID tenantId, UUID knowledgeBaseId) {
    List<String> models =
        jdbc.queryForList(
            "SELECT embedding_model FROM " + TABLE + " WHERE tenant_id = ? AND id = ?",
            String.class,
            tenantId,
            knowledgeBaseId);
    return models.isEmpty() ? Optional.empty() : Optional.of(models.get(0));
  }
}
