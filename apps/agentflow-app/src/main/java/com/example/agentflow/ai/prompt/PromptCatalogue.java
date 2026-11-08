package com.example.agentflow.ai.prompt;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.MessageRole;
import com.example.agentflow.ai.application.PromptVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The prompt catalogue (S09-04): it loads the bundled prompt templates from {@code resources/ai/},
 * assigns each a {@link PromptVersion} (a declared {@code version} plus a content {@code hash}),
 * and renders a prompt into the messages sent to the model.
 *
 * <p>The catalogue owns the <em>separation of policy from user content</em>: {@link #build} always
 * produces a fixed {@link MessageRole#SYSTEM} message from the template (the policy) followed by a
 * single {@link MessageRole#USER} message carrying the caller-injected content (the task and any
 * retrieved document fragments, which are untrusted). Policy and user content never share one
 * message, so a model can never read its own policy as user content, and the policy is the only
 * part that ships as a versioned, hashed resource.
 *
 * <p>The observable surface of a prompt is its {@link PromptVersion} (name / version / hash) and
 * the rendered messages; the catalogue never logs, hashes or otherwise exposes the injected
 * content, so the (untrusted) document content stays out of observability (invariant 8).
 */
public final class PromptCatalogue {

  private static final String RESOURCE_PREFIX = "ai/";
  private static final String RESOURCE_SUFFIX = ".txt";

  private final Map<String, PromptVersion> versions = new LinkedHashMap<>();
  private final Map<String, String> templates = new LinkedHashMap<>();

  /**
   * Loads every declared prompt. Each template is read from {@code resources/ai/<name>.txt}; a
   * missing template fails construction, so an incomplete {@code ai.prompt} declaration stops the
   * app booting (fail fast, like every other required configuration).
   *
   * @param refs the declared prompts (name + assigned version)
   */
  public PromptCatalogue(List<PromptRef> refs) {
    for (PromptRef ref : refs) {
      String name = ref.getName();
      String template = loadTemplate(name);
      versions.put(name, new PromptVersion(name, ref.getVersion(), sha256Hex(template)));
      templates.put(name, template);
    }
  }

  /**
   * @param name the prompt name
   * @return the {@link PromptVersion} (name / version / hash) for the prompt
   * @throws IllegalArgumentException if no prompt with {@code name} is declared
   */
  public PromptVersion versionOf(String name) {
    PromptVersion version = versions.get(name);
    if (version == null) {
      throw new IllegalArgumentException("unknown prompt: " + name);
    }
    return version;
  }

  /**
   * @param name the prompt name
   * @return the raw template text (the policy)
   * @throws IllegalArgumentException if no prompt with {@code name} is declared
   */
  public String text(String name) {
    String template = templates.get(name);
    if (template == null) {
      throw new IllegalArgumentException("unknown prompt: " + name);
    }
    return template;
  }

  /**
   * @return the declared prompt names, in declaration order (unmodifiable)
   */
  public List<String> names() {
    return List.copyOf(versions.keySet());
  }

  /**
   * Render a prompt into the messages sent to the model: a {@link MessageRole#SYSTEM} message with
   * the template (the policy) and a {@link MessageRole#USER} message with the caller-injected
   * content (the task plus any retrieved document fragments -- untrusted, prompt-injection
   * surface).
   *
   * @param name the prompt name
   * @param userContent the untrusted content to send to the model; must not be {@code null} or
   *     empty
   * @return {@code [SYSTEM(policy), USER(userContent)]}
   * @throws IllegalArgumentException if {@code name} is unknown or {@code userContent} is {@code
   *     null} or blank
   */
  public List<ChatMessage> build(String name, String userContent) {
    if (userContent == null || userContent.isBlank()) {
      throw new IllegalArgumentException("user content must not be null or blank");
    }
    List<ChatMessage> messages = new ArrayList<>(2);
    messages.add(new ChatMessage(MessageRole.SYSTEM, text(name)));
    messages.add(new ChatMessage(MessageRole.USER, userContent));
    return messages;
  }

  private String loadTemplate(String name) {
    String resource = RESOURCE_PREFIX + name + RESOURCE_SUFFIX;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("prompt template not found on classpath: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read prompt template: " + resource, exception);
    }
  }

  private static String sha256Hex(String template) {
    return sha256Hex(template.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content);
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(Character.forDigit((b >> 4) & 0xF, 16));
        builder.append(Character.forDigit(b & 0xF, 16));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 not available", exception);
    }
  }
}
