package com.example.agentflow.ai.prompt;

import jakarta.validation.constraints.NotBlank;

/**
 * A single prompt declaration from the {@code ai.prompt} configuration (S09-04): the template name
 * (matching {@code resources/ai/<name>.txt}) and the {@code version} assigned to it.
 *
 * <p>This is a configuration binding type, not a domain value: the {@code version} is
 * human-assigned and the {@code hash} is derived from the template content by the {@link
 * PromptCatalogue}. Blank values are rejected at binding time (the {@code @NotBlank} constraints),
 * so a missing version fails context startup rather than producing an empty prompt version.
 */
public class PromptRef {

  @NotBlank(message = "ai.prompt prompts require a non-blank name")
  private String name;

  @NotBlank(message = "ai.prompt prompts require a non-blank version")
  private String version;

  /**
   * @return the template name; matched against {@code resources/ai/<name>.txt}
   */
  public String getName() {
    return name;
  }

  /**
   * @param name the template name; matched against {@code resources/ai/<name>.txt}
   */
  public void setName(String name) {
    this.name = name;
  }

  /**
   * @return the version assigned to the template
   */
  public String getVersion() {
    return version;
  }

  /**
   * @param version the version assigned to the template
   */
  public void setVersion(String version) {
    this.version = version;
  }
}
