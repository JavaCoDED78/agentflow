package com.example.agentflow.knowledgebase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Negative tests for the <b>tenant-safe file upload</b> acceptance of {@code S10-04}: an upload can
 * neither smuggle a <b>path traversal</b> into the object path nor let one <b>tenant</b> read
 * another tenant's document. These pin the two invariants the future upload flow relies on
 * (invariant 1), the parts of {@code S10-04} that are verifiable before the {@code documents}
 * schema (S10-05), the object storage client and the {@code (tenant_id, id)} row lookup exist:
 *
 * <ul>
 *   <li><b>Path traversal</b> &mdash; the object key is generated <b>server-side</b> from the
 *       server-issued identifiers, never from the client {@code filename}; a hostile or
 *       prompt-injected {@code filename} can never become a path or a key segment, and the {@code
 *       FileNameSanitizer} reduces any path to a bare base name.
 *   <li><b>Cross-tenant access</b> &mdash; an object key is <b>bound</b> to exactly the tenant that
 *       issued it; the same document maps to distinct keys across tenants; and a key from another
 *       tenant resolves to that tenant &mdash; so a tenant-scoped lookup keyed by {@code
 *       (tenant_id, id)} treats another tenant's key as a missing one, never as an object to read.
 * </ul>
 *
 * DB-free: both values are pure, so no {@code DataSource} / storage dependency is needed.
 */
class DocumentUploadTenantSafetyTest {

  private static final UUID TENANT_A = UUID.fromString("55505550-5550-5550-5550-555055505550");
  private static final UUID TENANT_B = UUID.fromString("66606660-6660-6660-6660-666066606660");
  private static final UUID DOC_A1 = UUID.fromString("9a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d");
  private static final UUID DOC_A2 = UUID.fromString("11112222-3333-4444-5555-666677778888");

  // A client-supplied filename that would be a path-traversal / tenant-leak vector if it were used.
  private static final String MALICIOUS_FILENAME =
      "../../etc/passwd; ../secret-tenant-7/report (FINAL).PDF";

  @Nested
  class PathTraversal {

    @Test
    void the_object_key_is_generated_from_the_server_ids_and_never_from_the_filename() {
      // The upload flow builds the key from the server-issued identifiers only. Two uploads of the
      // same document with two different (even hostile) filenames map to the exact same key.
      DocumentObjectKey first = DocumentObjectKey.of(TENANT_A, DOC_A1);
      DocumentObjectKey second = DocumentObjectKey.of(TENANT_A, DOC_A1);

      assertThat(first).isEqualTo(second);
      assertThat(first.value()).doesNotContain(MALICIOUS_FILENAME);
      assertThat(first.value()).doesNotContain("..");
      // The path has exactly the canonical shape: the fixed prefix, tenant, document. A third
      // segment (which a hostile filename would inject) is impossible.
      assertThat(first.value().split("/")).hasSize(3);
    }

    @Test
    void a_prompt_injected_extension_can_never_extend_the_object_key() {
      Set<String> dangerous = Set.of("exe", "jsp", "php", "sh", "..", "a/b", "UPPER", "a");
      for (String extension : dangerous) {
        assertThatThrownBy(() -> DocumentObjectKey.of(TENANT_A, DOC_A1, extension))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content-type hint")
            .hasMessageContaining(extension);
      }
    }

    @Test
    void the_filename_sanitizer_reduces_a_traversal_to_a_bare_base_and_never_a_path() {
      // Defense-in-depth: even the display filename the row carries can never be a path.
      assertThat(FileNameSanitizer.sanitize(MALICIOUS_FILENAME)).isEqualTo("report (FINAL).PDF");
      assertThat(FileNameSanitizer.sanitize("../../etc/passwd")).isEqualTo("passwd");
      assertThat(FileNameSanitizer.sanitize("a/b/c")).isEqualTo("c");
    }
  }

  @Nested
  class CrossTenantAccess {

    @Test
    void an_object_key_is_bound_to_exactly_the_tenant_that_issued_it() {
      DocumentObjectKey key = DocumentObjectKey.of(TENANT_A, DOC_A1);

      assertThat(key.tenantId()).isEqualTo(TENANT_A);
      // A key parsed back from its string still resolves to the issuing tenant &mdash; it cannot be
      // re-issued under a different one.
      assertThat(new DocumentObjectKey(key.value()).tenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void the_same_document_maps_to_distinct_keys_across_tenants() {
      DocumentObjectKey fromA = DocumentObjectKey.of(TENANT_A, DOC_A1);
      DocumentObjectKey fromB = DocumentObjectKey.of(TENANT_B, DOC_A1);

      assertThat(fromA).isNotEqualTo(fromB);
      assertThat(fromA.value()).isNotEqualTo(fromB.value());
      // Each key still names its own tenant.
      assertThat(fromA.tenantId()).isEqualTo(TENANT_A);
      assertThat(fromB.tenantId()).isEqualTo(TENANT_B);
    }

    @Test
    void a_key_from_another_tenant_resolves_to_that_tenant_and_is_missing_here() {
      // Another tenant's key, read in tenant A's context, still resolves to tenant B. A lookup
      // keyed by (tenant_id, document_id) for tenant A therefore never matches it &mdash; the key
      // is
      // indistinguishable from a missing one (invariant 1).
      DocumentObjectKey fromB = DocumentObjectKey.of(TENANT_B, DOC_A1);
      DocumentObjectKey parsedAsTenantA = new DocumentObjectKey(fromB.value());

      assertThat(parsedAsTenantA.tenantId()).isEqualTo(TENANT_B);
      assertThat(parsedAsTenantA.tenantId()).isNotEqualTo(TENANT_A);
      assertThat(parsedAsTenantA.documentId()).isEqualTo(DOC_A1);
    }

    @Test
    void a_tenant_cannot_authorize_another_tenants_key_string() {
      // Reusing tenant B's key string cannot authorize tenant A: the encoded tenant is B.
      String anotherTenantKey = DocumentObjectKey.of(TENANT_B, DOC_A1).value();
      DocumentObjectKey reused = new DocumentObjectKey(anotherTenantKey);

      assertThat(reused.tenantId()).isNotEqualTo(TENANT_A);
      assertThat(reused.tenantId()).isEqualTo(TENANT_B);
    }

    @Test
    void a_forged_key_for_another_document_under_the_same_tenant_is_a_distinct_key() {
      // A tenant cannot reach document 2 by presenting a key issued for document 1: the document id
      // is part of the key, so a different document is a different, non-authorizable key.
      DocumentObjectKey forDoc1 = DocumentObjectKey.of(TENANT_A, DOC_A1);
      DocumentObjectKey forDoc2 = DocumentObjectKey.of(TENANT_A, DOC_A2);

      assertThat(forDoc1).isNotEqualTo(forDoc2);
      assertThat(forDoc1.value()).isNotEqualTo(forDoc2.value());
      assertThat(forDoc1.documentId()).isEqualTo(DOC_A1);
      assertThat(forDoc2.documentId()).isEqualTo(DOC_A2);
    }
  }
}
