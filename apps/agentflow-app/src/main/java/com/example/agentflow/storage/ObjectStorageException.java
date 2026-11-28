package com.example.agentflow.storage;

import java.util.Objects;
import java.util.Optional;

/**
 * The single provider-neutral exception thrown by every {@link ObjectStoragePort} operation and by
 * the contract value types (S07-06B, contract §2.3).
 *
 * <p>The thrown exception is the contract's failure taxonomy — no AWS SDK, Garage or
 * provider-specific exception type, class name, or message detail may escape through the contract
 * boundary in either direction. {@link #code()} carries the stable, retryability-bearing code;
 * {@link #getMessage()} is a caller-side explanation that <b>never</b> carries credentials, object
 * content, document text, prompts, unrestricted keys or the raw provider cause — the adapter maps
 * provider detail to the code and classification, not to the message (invariant 8). The optional
 * cause (when set) is carried by the caller's own stack-trace path, never logged by this module.
 *
 * <p>The optional {@link #requestId()} is the provider's request/correlation identifier for the
 * failed call — a safe, provider-neutral correlation value (never a credential, header or body —
 * invariant 8) that a caller or operator can use to trace the failure back to the provider request.
 * The adapter preserves it when the provider supplies one; a failure carrying no provider-side
 * identifier simply carries none — it is never fabricated. {@link #toString()} echoes only the code
 * and the optional correlation id — never the message (a bad message could carry a key) and never a
 * header or credential.
 *
 * <p>The same contract's caller-side rejections ({@code INVALID_KEY} / {@code INVALID_REQUEST}) are
 * thrown by the value types themselves, so a caller can catch one type and classify every contract
 * failure — including a structurally invalid request — through {@link ObjectStorageErrorCode}.
 */
public class ObjectStorageException extends RuntimeException {

  private final ObjectStorageErrorCode code;
  private final String requestId;

  /**
   * @param code the stable failure code; never {@code null}
   * @param message a caller-side explanation that carries no key, content, credential or prompt
   */
  public ObjectStorageException(ObjectStorageErrorCode code, String message) {
    this(code, message, null);
  }

  /**
   * @param code the stable failure code; never {@code null}
   * @param message a caller-side explanation that carries no key, content, credential or prompt
   * @param cause the underlying throwable, if any — never the raw provider exception
   */
  public ObjectStorageException(ObjectStorageErrorCode code, String message, Throwable cause) {
    this(code, message, cause, null);
  }

  /**
   * @param code the stable failure code; never {@code null}
   * @param message a caller-side explanation that carries no key, content, credential or prompt
   * @param cause the underlying throwable, if any — never the raw provider exception
   * @param requestId the provider's request/correlation identifier, if the provider supplied one;
   *     never a secret, header or body — never {@code null} handling is left to the caller
   */
  public ObjectStorageException(
      ObjectStorageErrorCode code, String message, Throwable cause, String requestId) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "ObjectStorageException code must not be null");
    String normalized = requestId == null ? null : requestId.trim();
    this.requestId = (normalized == null || normalized.isEmpty()) ? null : normalized;
  }

  /**
   * @return the stable provider-neutral failure code
   */
  public ObjectStorageErrorCode code() {
    return code;
  }

  /**
   * @return the provider's request/correlation identifier for the failed call, or {@code
   *     Optional.empty()} when the provider supplied none — never a fabricated value
   */
  public Optional<String> requestId() {
    return Optional.ofNullable(requestId);
  }

  @Override
  public String toString() {
    // Never echo the message (a bad message could carry a key) — the code and the correlation
    // id (a safe value) are the only safe surface for logs and traces.
    return requestId == null
        ? "ObjectStorageException[code=" + code + "]"
        : "ObjectStorageException[code=" + code + ", requestId=" + requestId + "]";
  }
}
