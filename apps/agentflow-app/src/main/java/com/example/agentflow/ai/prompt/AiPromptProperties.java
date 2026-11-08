package com.example.agentflow.ai.prompt;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code ai.prompt} configuration (S09-04): the set of prompt templates the platform bundles
 * and versions. Each entry declares a template {@code name} (matched against {@code
 * resources/ai/<name>.txt}) and a {@code version} to assign; the {@link PromptCatalogue} derives
 * the content hash.
 *
 * <p>Bound from {@code ai.prompt.prompts[]} with the project's typed-configuration idiom
 * ({@code @ConfigurationProperties} + {@code @Validated} + {@code @Valid}); a blank {@code name} or
 * {@code version} fails context startup as a {@code ConfigurationPropertiesBindException}, so an
 * incomplete declaration surfaces immediately rather than as a {@code NullPointerException} later.
 */
@ConfigurationProperties(prefix = "ai.prompt")
@Validated
public class AiPromptProperties {

  @Valid private final List<PromptRef> prompts = new ArrayList<>();

  /**
   * @return the declared prompts, in declaration order; never {@code null}
   */
  public List<PromptRef> getPrompts() {
    return prompts;
  }
}
