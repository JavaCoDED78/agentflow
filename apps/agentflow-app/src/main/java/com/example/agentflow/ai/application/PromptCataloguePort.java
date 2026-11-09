package com.example.agentflow.ai.application;

import java.util.List;

/**
 * The prompt-catalogue port (S09-06): the narrow {@code ai.application}-facing surface the Planner
 * needs to render a prompt -- a fixed {@code SYSTEM} policy plus a single untrusted {@code USER}
 * turn -- and to learn which prompt version it used.
 *
 * <p>It lives in {@code ai.application} (the {@code ai} public boundary) so the Planner never has
 * to import the {@code ai.prompt} implementation, which would close a package-level cycle: {@code
 * ai.prompt} depends on {@code ai.application} for {@link ChatMessage}, {@link MessageRole} and
 * {@link PromptVersion}. {@code ai.prompt.PromptCatalogue} implements this port, so the dependency
 * stays one-directional and the {@code S02-09} acyclicity gate stays green. See {@code ADR-006} for
 * why the Planner only ever proposes.
 */
public interface PromptCataloguePort {

  /**
   * @param name the prompt name (the Planner uses {@code "planner"})
   * @return the {@link PromptVersion} (name / version / hash) for the prompt; {@code
   *     IllegalArgumentException} if the prompt is not declared
   */
  PromptVersion versionOf(String name);

  /**
   * Render the prompt into the messages sent to the model: a {@link MessageRole#SYSTEM} message
   * with the template (the policy) followed by a {@link MessageRole#USER} message carrying the
   * caller-injected content (the task and any retrieved document fragments -- untrusted).
   *
   * @param name the prompt name
   * @param userContent the untrusted content to send to the model; must not be {@code null} or
   *     blank
   * @return {@code [SYSTEM(policy), USER(userContent)]}
   * @throws IllegalArgumentException if {@code name} is unknown or {@code userContent} is {@code
   *     null} or blank
   */
  List<ChatMessage> build(String name, String userContent);
}
