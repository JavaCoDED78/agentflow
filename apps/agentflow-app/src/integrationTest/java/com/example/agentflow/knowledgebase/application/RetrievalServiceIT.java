package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.KnowledgeBaseRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The {@code S10-14} acceptance end-to-end: {@link RetrievalService} driving the real
 * Flyway-migrated schema, the real {@link DocumentChunkRepository} / {@link
 * KnowledgeBaseRepository} and a deterministic in-memory {@link EmbeddingPort} — on {@code
 * pgvector/pgvector:0.8.6-pg18}, the exact image Compose pins (never {@code H2}, per {@code
 * AGENTS.md}).
 *
 * <p>The pivotal case is the acceptance criterion: two tenants holding collections with
 * <em>identical</em> embeddings (the maximally close case) — retrieval for one tenant never returns
 * the other's chunks, and retrieval is scoped to one knowledge base inside the tenant. The suite
 * also pins the bounded behaviour on top of the nearest-neighbour ordering: the relevance threshold
 * drops distant candidates, a requested {@code topK} caps the result, and a chunk whose embedding
 * is {@code NULL} (not yet indexed) never surfaces. It closes with the {@code S10-18} no-evidence
 * acceptance: {@code groundedAnswer} grounds in the calling tenant's own fragments and reports an
 * evidence gap for an empty / cross-tenant retrieval, never fabricating a citation.
 */
@Testcontainers
class RetrievalServiceIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static RetrievalService service;
  private static DocumentChunkRepository chunkRepository;
  private static KnowledgeBaseRepository knowledgeBaseRepository;
  private static SimpleMeterRegistry meterRegistry;

  @BeforeAll
  static void migrateAndWire() throws SQLException {
    Flyway flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();

    DataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    chunkRepository = new DocumentChunkRepository(jdbc);
    knowledgeBaseRepository = new KnowledgeBaseRepository(jdbc);
    // Deterministic stand-in for the provider: every call answers the +x unit vector, so the
    // query vector (and thus every distance) is fully controlled by the fixture embeddings.
    EmbeddingPort queryEmbedding =
        request -> {
          float[] query = new float[768];
          query[0] = 1f;
          return new EmbeddingResponse(request.model(), List.of(query), null);
        };
    meterRegistry = new SimpleMeterRegistry();
    service =
        new RetrievalService(
            chunkRepository,
            knowledgeBaseRepository,
            queryEmbedding,
            10,
            50,
            0.5,
            meterRegistry,
            null);
  }

  /** The tenant/knowledge-base/document chain a chunk fixture needs. */
  private record Fixture(UUID tenantId, UUID knowledgeBaseId, UUID documentId) {}

  private static Fixture tenantWithKnowledgeBaseAndDocument(String slug) {
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')", UUID.randomUUID(), slug);
    UUID tenant = lookup("SELECT id FROM tenants WHERE slug = '" + slug + "'");
    UUID kb = uuid();
    jdbc.update(
        "INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model) VALUES (?, ?, ?, ?)",
        kb,
        tenant,
        slug + "-kb",
        "nomic-embed-text");
    UUID document = insertDocument(tenant, kb, slug);
    return new Fixture(tenant, kb, document);
  }

  private static UUID insertDocument(UUID tenant, UUID kb, String slug) {
    UUID document = uuid();
    jdbc.update(
        "INSERT INTO documents (id, tenant_id, knowledge_base_id, object_key, filename, "
            + "mime_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        document,
        tenant,
        kb,
        "documents/" + tenant + ".pdf",
        "sample.pdf",
        "application/pdf",
        1024L,
        "sha256-" + slug);
    return document;
  }

  private static UUID insertChunk(UUID tenant, UUID document, int index, String content) {
    UUID chunk = uuid();
    jdbc.update(
        "INSERT INTO document_chunks (id, tenant_id, document_id, pipeline_version, chunk_index, "
            + "page_from, page_to, content, content_hash, embedding) VALUES (?, ?, ?, 'v1', ?, 1, 1, "
            + "?, ?, ?::vector)",
        chunk,
        tenant,
        document,
        index,
        content,
        "hash-" + index,
        vectorLiteral(embeddingFor(content)));
    return chunk;
  }

  /** Insert a chunk with no embedding (the S10-11 nullable path: created, not yet indexed). */
  private static void insertUnindexedChunk(UUID tenant, UUID document, int index, String content) {
    jdbc.update(
        "INSERT INTO document_chunks (id, tenant_id, document_id, pipeline_version, chunk_index, "
            + "page_from, page_to, content, content_hash) VALUES (?, ?, ?, 'v1', ?, 1, 1, ?, ?)",
        uuid(),
        tenant,
        document,
        index,
        content,
        "hash-" + index);
  }

  /**
   * The fixture embedding vocabulary: every content maps to a deterministic 768-d unit vector on
   * the +x/+y plane, so the cosine distance to the query vector (+x) is known exactly — "x" is
   * identical to the query (distance 0), "tilt" is close (~0.011), "y" is orthogonal (distance 1).
   */
  private static float[] embeddingFor(String content) {
    return switch (content) {
      case "x" -> unit768(1f, 0f, 0f);
      case "tilt" -> unit768(1f, 0.15f, 0f);
      case "tilt2" -> unit768(1f, 0.3f, 0f);
      case "y" -> unit768(0f, 1f, 0f);
      default -> throw new IllegalArgumentException("no fixture embedding for " + content);
    };
  }

  private static float[] unit768(float a, float b, float c) {
    float[] v = new float[768];
    double norm = Math.sqrt(a * a + b * b + c * c);
    v[0] = a / (float) norm;
    v[1] = b / (float) norm;
    v[2] = c / (float) norm;
    return v;
  }

  private static String vectorLiteral(float[] values) {
    StringBuilder sb = new StringBuilder(values.length * 8 + 2);
    sb.append('[');
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Float.toString(values[i]));
    }
    sb.append(']');
    return sb.toString();
  }

  private static UUID uuid() {
    return UUID.randomUUID();
  }

  private static UUID lookup(String sql) {
    try (Statement st = connection().createStatement();
        java.sql.ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getObject(1, UUID.class) : null;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  /**
   * The S10-14 acceptance: two tenants hold collections whose chunks carry <em>identical</em>
   * embeddings — the maximally close case. Retrieval for either tenant returns only its own chunks.
   */
  @Test
  void retrieval_never_returns_another_tenants_chunks_even_at_maximal_similarity() {
    Fixture tenantA = tenantWithKnowledgeBaseAndDocument("ret-tenant-a");
    Fixture tenantB = tenantWithKnowledgeBaseAndDocument("ret-tenant-b");

    // Identical embeddings on both sides: no cross-tenant chunk can be "closer" than identical.
    insertChunk(tenantA.tenantId(), tenantA.documentId(), 0, "x");
    insertChunk(tenantB.tenantId(), tenantB.documentId(), 0, "x");

    SearchResult forA =
        service.search(tenantA.tenantId(), tenantA.knowledgeBaseId(), new SearchRequest("q", null));
    assertThat(forA.chunks()).hasSize(1);
    assertThat(forA.chunks().get(0).score())
        .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(forA.chunks().get(0).documentId()).isEqualTo(tenantA.documentId());

    SearchResult forB =
        service.search(tenantB.tenantId(), tenantB.knowledgeBaseId(), new SearchRequest("q", null));
    assertThat(forB.chunks()).hasSize(1);
    assertThat(forB.chunks().get(0).documentId()).isEqualTo(tenantB.documentId());
  }

  /** Retrieval is scoped to one knowledge base: a sibling collection's chunk never surfaces. */
  @Test
  void retrieval_is_scoped_to_one_knowledge_base() {
    Fixture first = tenantWithKnowledgeBaseAndDocument("ret-kb-first");
    UUID kb2 =
        lookup(
            "INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model) VALUES ('"
                + uuid()
                + "', '"
                + first.tenantId()
                + "', 'ret-kb-second', 'nomic-embed-text') RETURNING id");
    UUID doc2 = insertDocument(first.tenantId(), kb2, "ret-kb-second");

    insertChunk(first.tenantId(), first.documentId(), 0, "x");
    insertChunk(first.tenantId(), doc2, 0, "x");

    SearchResult inFirst =
        service.search(first.tenantId(), first.knowledgeBaseId(), new SearchRequest("q", null));
    assertThat(inFirst.chunks()).hasSize(1);
    assertThat(inFirst.chunks().get(0).documentId()).isEqualTo(first.documentId());

    SearchResult inSecond = service.search(first.tenantId(), kb2, new SearchRequest("q", null));
    assertThat(inSecond.chunks()).hasSize(1);
    assertThat(inSecond.chunks().get(0).documentId()).isEqualTo(doc2);
  }

  /** Invariant 1: another tenant's knowledge-base id is indistinguishable from a missing one. */
  @Test
  void a_cross_tenant_knowledge_base_yields_an_empty_result() {
    Fixture tenantA = tenantWithKnowledgeBaseAndDocument("ret-xkb-a");
    Fixture tenantB = tenantWithKnowledgeBaseAndDocument("ret-xkb-b");
    insertChunk(tenantA.tenantId(), tenantA.documentId(), 0, "x");
    insertChunk(tenantB.tenantId(), tenantB.documentId(), 0, "x");

    SearchResult result =
        service.search(tenantA.tenantId(), tenantB.knowledgeBaseId(), new SearchRequest("q", null));

    assertThat(result.chunks()).isEmpty();
  }

  /** The relevance threshold drops candidates whose cosine distance exceeds it. */
  @Test
  void retrieval_drops_chunks_beyond_the_relevance_threshold() {
    Fixture fixture = tenantWithKnowledgeBaseAndDocument("ret-threshold");
    insertChunk(fixture.tenantId(), fixture.documentId(), 0, "x"); // distance 0 — kept
    insertChunk(fixture.tenantId(), fixture.documentId(), 1, "tilt"); // ~0.011 — kept
    insertChunk(fixture.tenantId(), fixture.documentId(), 2, "y"); // 1.0 — beyond 0.5, dropped

    SearchResult result =
        service.search(fixture.tenantId(), fixture.knowledgeBaseId(), new SearchRequest("q", null));

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).score())
        .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(result.chunks().get(1).score())
        .isCloseTo(0.011, org.assertj.core.data.Offset.offset(1e-3));
    assertThat(result.chunks()).allMatch(chunk -> chunk.score() <= 0.5);
  }

  /**
   * A requested topK caps the result at the closest chunks even when more candidates are within the
   * relevance threshold.
   */
  @Test
  void retrieval_limits_the_result_to_the_requested_top_k() {
    Fixture fixture = tenantWithKnowledgeBaseAndDocument("ret-topk");
    insertChunk(fixture.tenantId(), fixture.documentId(), 0, "x"); // distance 0
    insertChunk(fixture.tenantId(), fixture.documentId(), 1, "tilt"); // ~0.011
    insertChunk(fixture.tenantId(), fixture.documentId(), 2, "tilt2"); // ~0.042
    // Three candidates are within the threshold; the cap keeps the two closest, in order.
    SearchResult result =
        service.search(fixture.tenantId(), fixture.knowledgeBaseId(), new SearchRequest("q", 2));

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).score()).isLessThan(result.chunks().get(1).score());
    assertThat(result.chunks().get(1).score())
        .isCloseTo(0.011, org.assertj.core.data.Offset.offset(1e-3));
  }

  /** A chunk created but not yet indexed (NULL embedding) never surfaces as a false hit. */
  @Test
  void retrieval_never_returns_a_chunk_without_an_embedding() {
    Fixture fixture = tenantWithKnowledgeBaseAndDocument("ret-null-emb");
    insertUnindexedChunk(fixture.tenantId(), fixture.documentId(), 0, "unindexed fragment");
    insertChunk(fixture.tenantId(), fixture.documentId(), 1, "x");

    SearchResult result =
        service.search(fixture.tenantId(), fixture.knowledgeBaseId(), new SearchRequest("q", null));

    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().get(0).documentId()).isEqualTo(fixture.documentId());
    assertThat(result.chunks()).noneMatch(chunk -> chunk.content().equals("unindexed fragment"));
  }

  /** A collection with no indexed chunks at all is a plain empty answer, not an error. */
  @Test
  void an_empty_collection_yields_an_empty_result() {
    Fixture fixture = tenantWithKnowledgeBaseAndDocument("ret-empty");

    SearchResult result =
        service.search(fixture.tenantId(), fixture.knowledgeBaseId(), new SearchRequest("q", null));

    assertThat(result.chunks()).isEmpty();
  }

  /**
   * The S10-16 hybrid acceptance on real data: a chunk whose text matches the query is found by
   * both halves and deduplicated, a chunk matched by one half only is kept, and the fused result
   * carries no duplicate chunk ids.
   */
  @Test
  void hybrid_search_fuses_the_halves_without_duplicate_chunks() {
    Fixture fixture = tenantWithKnowledgeBaseAndDocument("ret-hybrid");
    insertChunk(fixture.tenantId(), fixture.documentId(), 0, "x"); // both halves
    insertChunk(fixture.tenantId(), fixture.documentId(), 1, "tilt"); // vector half only
    insertChunk(fixture.tenantId(), fixture.documentId(), 2, "tilt2"); // vector half only

    SearchResult result =
        service.hybridSearch(
            fixture.tenantId(), fixture.knowledgeBaseId(), new SearchRequest("x", null));

    // "x" is matched by both halves and emitted once (dedup); the two vector-only chunks are kept.
    assertThat(result.chunks()).hasSize(3);
    assertThat(result.chunks().stream().map(ChunkDto::id).toList()).doesNotHaveDuplicates();
    assertThat(result.chunks().stream().map(ChunkDto::content).toList()).contains("x");
    // The chunk matched by both halves outranks the vector-only chunks (reciprocal-rank fusion).
    assertThat(result.chunks().get(0).content()).isEqualTo("x");
    // The hybrid meter recorded, carrying no tenant or document label (invariant 9).
    assertThat(meterRegistry.find(RetrievalService.METRIC_HYBRID).meters()).isNotEmpty();
    List<Meter> hybridMeters =
        meterRegistry.find(RetrievalService.METRIC_HYBRID).meters().stream().toList();
    assertThat(hybridMeters).hasSize(1);
    assertThat(hybridMeters.get(0).getId().getTags()).isEmpty();
    assertThat(meterRegistry.find(RetrievalService.METRIC_DURATION).meters()).isNotEmpty();
  }

  /**
   * The hybrid search is tenant-scoped on real data: it never returns another tenant's chunk, even
   * though that tenant's collection holds an identical "x" chunk (the maximally close case).
   */
  @Test
  void hybrid_search_never_returns_another_tenants_chunks() {
    Fixture tenantA = tenantWithKnowledgeBaseAndDocument("ret-hybrid-a");
    Fixture tenantB = tenantWithKnowledgeBaseAndDocument("ret-hybrid-b");
    insertChunk(tenantA.tenantId(), tenantA.documentId(), 0, "x");
    insertChunk(tenantB.tenantId(), tenantB.documentId(), 0, "x");

    SearchResult result =
        service.hybridSearch(
            tenantA.tenantId(), tenantA.knowledgeBaseId(), new SearchRequest("x", null));

    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().get(0).documentId()).isEqualTo(tenantA.documentId());
  }

  /**
   * The S10-17 acceptance on real data: {@link RetrievalService#citations} builds one citation per
   * fused chunk, each carrying the parent document's display name, and every citation resolves to
   * the calling tenant's <em>own</em> chunk — never to another tenant's document. An empty
   * (cross-tenant) retrieval yields no citation at all, never an invented one.
   */
  @Test
  void citations_resolve_to_the_calling_tenants_own_fragment() {
    Fixture tenantA = tenantWithKnowledgeBaseAndDocument("ret-cite-a");
    Fixture tenantB = tenantWithKnowledgeBaseAndDocument("ret-cite-b");
    UUID chunkA = insertChunk(tenantA.tenantId(), tenantA.documentId(), 0, "x");
    insertChunk(tenantB.tenantId(), tenantB.documentId(), 0, "x");
    String documentName =
        jdbc.queryForObject(
            "SELECT filename FROM documents WHERE id = ?", String.class, tenantA.documentId());

    List<CitationDto> forA =
        service.citations(
            tenantA.tenantId(), tenantA.knowledgeBaseId(), new SearchRequest("x", null));

    // One citation per fused chunk, resolving to A's own chunk and carrying the document label.
    assertThat(forA).hasSize(1);
    CitationDto citation = forA.get(0);
    assertThat(citation.chunkId()).isEqualTo(chunkA);
    assertThat(citation.documentId()).isEqualTo(tenantA.documentId());
    assertThat(citation.documentName()).isEqualTo(documentName);
    assertThat(citation.score()).isGreaterThanOrEqualTo(0.0);

    // An empty (cross-tenant) retrieval yields no citation at all — never an invented one.
    List<CitationDto> forB =
        service.citations(
            tenantA.tenantId(), tenantB.knowledgeBaseId(), new SearchRequest("x", null));
    assertThat(forB).isEmpty();
  }

  /**
   * The S10-18 no-evidence acceptance on real data: {@link RetrievalService#groundedAnswer} reports
   * a {@link GroundedAnswer.Grounded} — one citation per fused chunk, each resolving to the calling
   * tenant's <em>own</em> chunk and carrying the document label — when the hybrid retrieval returns
   * evidence, and a {@link GroundedAnswer.InsufficientEvidence} gap (with the call's {@code
   * searchId}) for an empty or cross-tenant retrieval, never a fabricated citation (invariant 1).
   */
  @Test
  void groundedAnswer_grounds_in_the_calling_tenants_own_evidence_or_reports_a_gap() {
    Fixture tenantA = tenantWithKnowledgeBaseAndDocument("ret-ground-a");
    Fixture tenantB = tenantWithKnowledgeBaseAndDocument("ret-ground-b");
    UUID chunkA = insertChunk(tenantA.tenantId(), tenantA.documentId(), 0, "x");
    insertChunk(tenantB.tenantId(), tenantB.documentId(), 0, "x");
    UUID search = uuid();
    String documentName =
        jdbc.queryForObject(
            "SELECT filename FROM documents WHERE id = ?", String.class, tenantA.documentId());

    // Non-empty hybrid retrieval → a grounding in the real, tenant-scoped citations.
    GroundedAnswer grounded =
        service.groundedAnswer(
            tenantA.tenantId(), tenantA.knowledgeBaseId(), new SearchRequest("x", null), search);
    assertThat(grounded).isInstanceOf(GroundedAnswer.Grounded.class);
    CitationDto citation = ((GroundedAnswer.Grounded) grounded).citations().get(0);
    assertThat(citation.chunkId()).isEqualTo(chunkA);
    assertThat(citation.documentId()).isEqualTo(tenantA.documentId());
    assertThat(citation.documentName()).isEqualTo(documentName);
    assertThat(citation.snippet()).isNotBlank();

    // An empty (cross-tenant) retrieval is an evidence gap carrying the call's searchId.
    GroundedAnswer gap =
        service.groundedAnswer(
            tenantA.tenantId(), tenantB.knowledgeBaseId(), new SearchRequest("x", null), search);
    assertThat(gap).isInstanceOf(GroundedAnswer.InsufficientEvidence.class);
    assertThat(((GroundedAnswer.InsufficientEvidence) gap).searchId()).isEqualTo(search);
  }
}
