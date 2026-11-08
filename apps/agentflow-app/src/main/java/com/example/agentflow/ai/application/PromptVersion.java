package com.example.agentflow.ai.application;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The version identity of a prompt template (S09-04).
 *
 * <p>This is a contract value type (part of the provider-agnostic {@code ai.application} port): a
 * model attempt (each call to {@link ChatModelPort}) carries one of these so the prompt the model
 * planned against is recorded and traceable. It is the observable surface of a prompt: the {@code
 * name} it refers to, the {@code version} assigned to it in the {@code ai.prompt} configuration,
 * and the {@code hash} -- a SHA-256 digest of the template file's bytes. It deliberately holds
 * <em>no</em> prompt text and no user/document content, so the version carried per attempt never
 * leaks the prompt or the (untrusted) content injected into it.
 *
 * <p>The {@code version} is assigned in configuration (bump it whenever the template changes); the
 * {@code hash} is derived from the template content, so a content change is always detectable even
 * before the version is bumped. The {@code ai.prompt} catalogue renders this value from a template.
 *
 * @param name the prompt template name (for example {@code "planner"}); non-null, non-blank
 * @param version the version assigned to the template; non-null, non-blank
 * @param hash the SHA-256 hex digest (64 lowercase hex characters) of the template bytes; non-null
 */
public record PromptVersion(String name, String version, String hash) {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code name}, {@code version} or {@code hash} is {@code null}
   * @throws IllegalArgumentException if {@code name} or {@code version} is blank, or {@code hash}
   *     is not a 64-character lowercase SHA-256 hex digest
   */
  public PromptVersion {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("prompt name must not be blank");
    }
    Objects.requireNonNull(version, "version must not be null");
    if (version.isBlank()) {
      throw new IllegalArgumentException("prompt version must not be blank");
    }
    Objects.requireNonNull(hash, "hash must not be null");
    if (!SHA256_HEX.matcher(hash).matches()) {
      throw new IllegalArgumentException(
          "prompt version hash must be a 64-character lowercase SHA-256 hex digest");
    }
  }
}
