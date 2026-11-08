package com.example.agentflow.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.MessageRole;
import com.example.agentflow.ai.application.PromptVersion;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S09-04 tests for the {@link PromptCatalogue} (DB-free): the catalogue loads the bundled template,
 * derives a real content hash, separates the fixed policy (SYSTEM) from the untrusted injected
 * content (USER), and exposes only the {@link PromptVersion} — never the prompt text or the
 * content, so the (untrusted) document content stays out of observability.
 */
class PromptCatalogueTest {

  private static final String NAME = "planner";

  private PromptCatalogue catalogue() {
    return new PromptCatalogue(List.of(promptRef(NAME, "1")));
  }

  private static PromptRef promptRef(String name, String version) {
    PromptRef ref = new PromptRef();
    ref.setName(name);
    ref.setVersion(version);
    return ref;
  }

  @Test
  void loads_the_declared_prompt() {
    assertThat(catalogue().names()).containsExactly(NAME);
  }

  @Test
  void version_carries_the_assigned_version_and_a_real_content_hash() {
    PromptVersion version = catalogue().versionOf(NAME);

    assertThat(version.name()).isEqualTo(NAME);
    assertThat(version.version()).isEqualTo("1");
    // The hash is the SHA-256 of the template file bytes — not fabricated.
    assertThat(version.hash()).isEqualTo(sha256Hex(resourceBytes(NAME)));
  }

  @Test
  void build_separates_policy_from_user_content() {
    String injected = "Task: summarise.\nDocument fragment: <untrusted data>";
    List<ChatMessage> messages = catalogue().build(NAME, injected);

    assertThat(messages).hasSize(2);
    // SYSTEM is the fixed policy template — the same bytes the catalogue loaded.
    assertThat(messages.get(0).role()).isEqualTo(MessageRole.SYSTEM);
    assertThat(messages.get(0).content()).isEqualTo(catalogue().text(NAME));
    // USER is exactly the injected (untrusted) content — never mixed into the policy message.
    assertThat(messages.get(1).role()).isEqualTo(MessageRole.USER);
    assertThat(messages.get(1).content()).isEqualTo(injected);
  }

  @Test
  void build_rejects_blank_user_content() {
    assertThatThrownBy(() -> catalogue().build(NAME, "   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void version_of_an_unknown_prompt_is_rejected() {
    assertThatThrownBy(() -> catalogue().versionOf("missing"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_catalogue_with_no_declared_prompts_has_no_content() {
    PromptCatalogue empty = new PromptCatalogue(List.of());

    assertThat(empty.names()).isEmpty();
    assertThatThrownBy(() -> empty.versionOf(NAME)).isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] resourceBytes(String name) {
    try (InputStream in =
        PromptCatalogueTest.class.getClassLoader().getResourceAsStream("ai/" + name + ".txt")) {
      assertThat(in).as("the template must be on the classpath").isNotNull();
      return in.readAllBytes();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read the template for hashing", exception);
    }
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
