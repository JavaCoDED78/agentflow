package com.example.agentflow.knowledgebase.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The object-key convention for a document in object storage (Garage, S3-compatible) — {@code S10-02}.
 *
 * <p>The key is generated <b>server-side</b> from two server-issued identifiers, never from the
 * client-supplied {@code filename}:
 *
 * <pre>
 *   documents/&lt;tenantId&gt;/&lt;documentId&gt;[.&lt;ext&gt;]
 * </pre>
 *
 * <ul>
 *   <li><b>tenantId</b> &mdash; the verified tenant (invariant 1). It is a namespacing prefix only;
 *       the caller must pass the tenant taken from the verified security context / the {@code
 *       documents} row re-checked against PostgreSQL, never from the request body, query or a Kafka
 *       header.</li>
 *   <li><b>documentId</b> &mdash; the server-issued UUID of the {@code documents} row. The same
 *       document always maps to the same key, so the key is stable and the upload can be
 *       deduplicated by it.</li>
 *   <li><b>ext</b> &mdash; an <em>optional</em> content-type hint, appended only when a
 *       <b>sanitised</b>, whitelisted extension is supplied. It is a hint, never the filename: an
 *       untrusted or non-whitelisted extension is rejected (see {@link #of(UUID, UUID, String)}).
 *       The authoritative content type is the {@code documents.mime_type} column, not the key.</li>
 * </ul>
 *
 * <p>Because the key is built from UUIDs (lowercase hex + dashes) plus an optional {@code .ext} of
 * {@code [a-z0-9]{2,10}} under a single fixed {@code documents/} root, it can never carry a path
 * separator, a {@code ..} traversal or a client filename (invariant 1 / {@code S10-04): a hostile or
 * prompt-injected filename cannot change it, and the same document always maps to one key. Tenant
 * isolation stays an app-layer decision (invariant 1) &mdash; the key is only a namespacing
 * defence-in-depth &mdash; and the {@code documents} row is always looked up by {@code (tenant_id,
 * id)} before the key is ever used to read an object, so another tenant's key is indistinguishable
 * from a missing one.</p>
 */
public final class DocumentObjectKey {

  /** The fixed, server-owned path root every document object key lives under. */
  public static final String ROOT = "documents/";

  /**
   * Content-type hints that may be appended to a key. The set is small and fixed on purpose: the
   * extension is never taken from the client filename, so an attacker cannot smuggle a dangerous
   * suffix (for example {@code .jsp} or {@code .php}) into the object path. The stored {@code
   * mime_type} is the source of truth for the payload's type.
   */
  private static final Set<String> SANITISED_EXTENSIONS =
      Set.of("pdf", "txt", "md", "rtf", "html", "csv", "json", "xml", "png", "jpg", "jpeg", "gif");

  /**
   * Canonical shape: {@code documents/&lt;tenantId&gt;/&lt;documentId&gt;[.&lt;ext&gt;]} where the
   * two UUIDs are lowercase ({@code UUID#toString()} form) and {@code <ext>} is {@code
   * [a-z0-9]{2,10}}. The {@code matches()} anchor means no further {@code /}, no {@code ..} and no
   * control character can ever be part of a key.
   */
  private static final Pattern KEY_PATTERN =
      Pattern.compile(
          "documents/"
              + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/"
              + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
              + "(\\.[a-z0-9]{2,10})?");

  private final String value;
  private final UUID tenantId;
  private final UUID documentId;
  private final String extension; // null when the key carries no content-type hint

  /**
   * @param value the fully-formed object key; validated against {@link #KEY_PATTERN}, and any
   *     extension against {@link #SANITISED_EXTENSIONS}. Used on the read path to parse a key the
   *     app generated, so it must accept exactly the keys {@link #of(UUID, UUID, String)} can emit.
   */
  public DocumentObjectKey(String value) {
    String v = Objects.requireNonNull(value, "value must not be null");
    var m = KEY_PATTERN.matcher(v);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "invalid object key (expected " + ROOT + "<tenantId>/<documentId>[.<ext>]): " + value);
    }
    this.tenantId = UUID.fromString(m.group(1));
    this.documentId = UUID.fromString(m.group(2));
    String ext = m.group(3);
    this.extension = ext == null ? null : ext.substring(1);
    if (this.extension != null && !SANITISED_EXTENSIONS.contains(this.extension)) {
      throw new IllegalArgumentException(
          "object key extension is not a permitted content-type hint: " + this.extension);
    }
    this.value = v;
  }

  /**
   * Build the key for a document from two server-issued UUIDs. The key never contains the filename
   * (see the class Javadoc).
   *
   * @param tenantId the verified tenant the object belongs to (the namespacing prefix)
   * @param documentId the server-issued id of the {@code documents} row
   * @return {@code documents/&lt;tenantId&gt;/&lt;documentId&gt;}
   */
  public static DocumentObjectKey of(UUID tenantId, UUID documentId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(documentId, "documentId must not be null");
    return new DocumentObjectKey(ROOT + tenantId.toString() + "/" + documentId.toString());
  }

  /**
   * Build the key for a document with a content-type hint appended. The extension is validated
   * against {@link #SANITISED_EXTENSIONS}; an untrusted or non-whitelisted suffix is rejected so it
   * can never become part of the object path.
   *
   * @param tenantId the verified tenant the object belongs to (the namespacing prefix)
   * @param documentId the server-issued id of the {@code documents} row
   * @param extension a sanitised content-type hint (for example {@code "pdf"}); rejected unless it
   *     is in {@link #SANITISED_EXTENSIONS}
   * @return {@code documents/&lt;tenantId&gt;/&lt;documentId&gt;.&lt;ext&gt;}
   */
  public static DocumentObjectKey of(UUID tenantId, UUID documentId, String extension) {
    Objects.requireNonNull(extension, "extension must not be null");
    if (!SANITISED_EXTENSIONS.contains(extension)) {
      throw new IllegalArgumentException(
          "extension is not a permitted content-type hint: " + extension);
    }
    return new DocumentObjectKey(
        ROOT + tenantId.toString() + "/" + documentId.toString() + "." + extension);
  }

  /**
   * @return the fully-formed key, for example {@code documents/5b4c.../9f1a...}
   */
  public String value() {
    return value;
  }

  /**
   * @return the tenant the key is namespaced under (the first segment after the root)
   */
  public UUID tenantId() {
    return tenantId;
  }

  /**
   * @return the server-issued document id the key points at (the second segment after the root)
   */
  public UUID documentId() {
    return documentId;
  }

  /**
   * @return the content-type hint, if the key carries one
   */
  public Optional<String> extension() {
    return Optional.ofNullable(extension);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DocumentObjectKey other)) {
      return false;
    }
    return value.equals(other.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
