package com.example.agentflow.knowledgebase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DocumentObjectKey} convention ({@code S10-02}). They pin the acceptance
 * that an object key is generated <b>server-side</b> from the server-issued identifiers, never from
 * the client filename, and that a hostile filename can never become an object path (invariant 1 /
 * {@code S10-04}). DB-free: the value is pure.
 */
class DocumentObjectKeyTest {

  private static final UUID TENANT = UUID.fromString("55505550-5550-5550-5550-555055505550");
  private static final UUID DOCUMENT = UUID.fromString("9a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d");

  // A client-supplied filename that would be a path-traversal / tenant-leak vector if it were used.
  private static final String MALICOUS_FILENAME =
      "../../etc/passwd; ../secret-tenant-7/report (FINAL).PDF";

  @Test
  void key_is_generated_from_the_server_ids_and_namespaced_by_tenant() {
    DocumentObjectKey key = DocumentObjectKey.of(TENANT, DOCUMENT);

    assertThat(key.value()).isEqualTo(DocumentObjectKey.ROOT + TENANT + "/" + DOCUMENT);
    assertThat(key.tenantId()).isEqualTo(TENANT);
    assertThat(key.documentId()).isEqualTo(DOCUMENT);
    assertThat(key.extension()).isEmpty();
  }

  @Test
  void the_client_filename_is_never_used_as_the_path() {
    // Even if a caller (or a prompt-injected filename) hands over a hostile name, it cannot change
    // the key: the key is derived only from the server-issued identifiers.
    DocumentObjectKey key = DocumentObjectKey.of(TENANT, DOCUMENT);

    assertThat(key.value()).doesNotContain(MALICOUS_FILENAME);
    // The path has exactly the canonical shape: the fixed prefix, tenant, document — a third
    // segment or ".." (which a hostile filename would inject) is impossible.
    assertThat(key.value()).contains("/");
    assertThat(key.value().split("/")).hasSize(3);
    assertThat(key.value()).doesNotContain("..");
    assertThat(key.value()).isEqualTo(DocumentObjectKey.ROOT + TENANT + "/" + DOCUMENT);
  }

  @Test
  void key_can_never_carry_a_path_traversal() {
    // No additional path segment, no "..", no null byte: the shape is
    // documents/<uuid>/<uuid>[.ext].
    assertThatThrownBy(() -> DocumentObjectKey.of(TENANT, DOCUMENT, "../evil"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extension");
  }

  @Nested
  class WithExtension {

    @Test
    void a_whitelisted_extension_is_appended_as_a_hint_only() {
      DocumentObjectKey key = DocumentObjectKey.of(TENANT, DOCUMENT, "pdf");

      assertThat(key.value()).isEqualTo(DocumentObjectKey.ROOT + TENANT + "/" + DOCUMENT + ".pdf");
      assertThat(key.extension()).hasValue("pdf");
      // The document id is still the second segment; the extension is not a path.
      assertThat(key.documentId()).isEqualTo(DOCUMENT);
    }

    @Test
    void an_untrusted_extension_is_rejected() {
      Set<String> dangerous = Set.of("exe", "jsp", "php", "sh", "..", "a/b", "UPPER", "a");
      for (String ext : dangerous) {
        assertThatThrownBy(() -> DocumentObjectKey.of(TENANT, DOCUMENT, ext))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content-type hint")
            .hasMessageContaining(ext);
      }
    }
  }

  @Nested
  class CanonicalShape {

    @Test
    void a_key_generated_by_the_factory_round_trips_through_the_constructor() {
      DocumentObjectKey generated = DocumentObjectKey.of(TENANT, DOCUMENT, "json");

      DocumentObjectKey parsed = new DocumentObjectKey(generated.value());

      assertThat(parsed).isEqualTo(generated);
      assertThat(parsed.tenantId()).isEqualTo(TENANT);
      assertThat(parsed.documentId()).isEqualTo(DOCUMENT);
      assertThat(parsed.extension()).hasValue("json");
    }

    @Test
    void a_key_with_a_non_whitelisted_extension_is_rejected_on_the_constructor() {
      // The constructor must reject exactly the keys the factory would emit: any extension not in
      // the
      // whitelist.
      assertThatThrownBy(
              () ->
                  new DocumentObjectKey(DocumentObjectKey.ROOT + TENANT + "/" + DOCUMENT + ".exe"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("content-type hint");
    }

    @Test
    void keys_must_have_the_exact_uuid_uuid_shape_and_no_extra_path() {
      String root = DocumentObjectKey.ROOT;
      assertThatThrownBy(() -> new DocumentObjectKey("documents/not-a-uuid/" + DOCUMENT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new DocumentObjectKey("weird/" + TENANT + "/" + DOCUMENT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new DocumentObjectKey(root + TENANT + "/" + DOCUMENT + "/extra"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new DocumentObjectKey(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Test
  void value_equality_is_by_the_full_key_string() {
    DocumentObjectKey a = DocumentObjectKey.of(TENANT, DOCUMENT);
    DocumentObjectKey b = new DocumentObjectKey(a.value());
    DocumentObjectKey c = DocumentObjectKey.of(TENANT, UUID.randomUUID());

    assertThat(a).isEqualTo(b);
    assertThat(a).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(c);
    assertThat(a).isNotEqualTo("documents/" + TENANT + "/" + DOCUMENT);
  }
}
