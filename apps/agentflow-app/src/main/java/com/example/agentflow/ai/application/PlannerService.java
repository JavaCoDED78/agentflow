package com.example.agentflow.ai.application;

import com.example.agentflow.ai.domain.plan.PlannerPlan;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The Planner (S09-06): it asks the model for a typed plan through the {@link ChatModelPort} and
 * turns the response into a {@link PlannerPlan}, never executing it.
 *
 * <p>This is {@code ADR-006} in code: the model <em>proposes</em>, the backend <em>authorizes and
 * executes</em>. The service renders the fixed policy (the bundled {@code resources/ai/planner.txt}
 * prompt) and appends the caller's task and allowed-tool list as a single, untrusted {@code USER}
 * turn, so a model can never read its own policy as user content ({@link PromptCataloguePort#build}
 * owns that split). The model returns a structured plan through {@link
 * ChatOptions#outputSchemaJson()}; the backend validates it server-side before any step runs
 * ({@code S09-07}). Referencing a tool is <em>not</em> execution and is <em>not</em> permission —
 * the backend re-checks the registry and the caller's scopes.
 *
 * <p>It depends on the provider through the neutral {@link ChatModelPort} (the only code, besides
 * the {@code provider} adapter, that names the port) and on {@link PromptCataloguePort} for the
 * policy. port is opt-in (a provider must be configured), so a missing {@link ChatModelPort} is a
 * clear, loud failure at call time, not a boot failure of the whole app (the provider is behind the
 * port, {@code ADR-014}).
 *
 * <p>The {@code model} id is read from the same property the OpenAI adapter targets ({@code
 * agentflow.ai.provider.openai.model}); it is the only provider-specific value this contract layer
 * carries, and only because the port is model-agnostic and the caller has to say which model to
 * call. A second provider ({@code S09-02} follow-up) would move the model id behind the port.
 */
@Service
public class PlannerService {

  /**
   * The structured-output schema the Planner requests: an object with a required {@code steps}
   * array of objects carrying exactly {@code id / type / toolId / dependsOn / arguments}. It is
   * neutral data on the contract; the provider adapter renders it into the SDK's own form (never
   * the contract). It deliberately exposes no field for chain-of-thought, reasoning, instructions
   * or a free-text escape hatch, so the model has nowhere to smuggle a capability (invariant 7).
   */
  private static final String OUTPUT_SCHEMA =
      """
          {
            "type": "object",
            "required": ["steps"],
            "properties": {
              "steps": {
                "type": "array",
                "items": {
                  "type": "object",
                  "required": ["id", "type"],
                  "properties": {
                    "id": { "type": "string" },
                    "type": { "type": "string" },
                    "toolId": { "type": "string" },
                    "dependsOn": { "type": "array", "items": { "type": "string" } },
                    "arguments": { "type": "object" }
                  }
                }
              }
            }
          }
          """;

  private final PlannerPlanParser parser = new PlannerPlanParser(new ObjectMapper());
  private final PromptCataloguePort promptCatalogue;
  private final String modelId;

  @Autowired(required = false)
  private final ChatModelPort chatModelPort;

  /**
   * @param promptCatalogue the prompt catalogue (always available); loads the planner policy
   * @param modelId the model the adapter targets by default (see the class comment)
   * @param chatModelPort the provider port; optional, so the app boots without a configured
   *     provider
   */
  public PlannerService(
      PromptCataloguePort promptCatalogue,
      @Value("${agentflow.ai.provider.openai.model:gpt-4o-mini}") String modelId,
      @Autowired(required = false) ChatModelPort chatModelPort) {
    this.promptCatalogue = promptCatalogue;
    this.modelId = modelId;
    this.chatModelPort = chatModelPort;
  }

  /**
   * Ask the model to propose a plan for {@code task} and return it with its attempt metadata.
   *
   * <p>The plan is a proposal, never an execution: {@link ChatModelPort#chat(ChatRequest)} performs
   * one chat completion and this service maps the response into a plan. Every step is validated and
   * re-authorized server-side before it runs ({@code S09-07}).
   *
   * @param task the caller's task text (the untrusted user content the Planner proposes for)
   * @param allowedTools the tool names the Planner may reference (the allowlist the backend gave
   *     it); {@code null} / empty means no tool references
   * @return the plan and its metadata (model id, prompt version, token usage)
   * @throws IllegalStateException if no {@link ChatModelPort} is configured (no provider selected)
   * @throws PlannerPlanParseException if the model returned no content, or content that is not a
   *     valid plan
   */
  public PlannerPlanResult propose(String task, List<String> allowedTools) {
    ChatModelPort port = this.chatModelPort;
    if (port == null) {
      throw new IllegalStateException(
          "no ChatModelPort configured: the Planner needs a model provider (set "
              + "agentflow.ai.provider)");
    }
    if (task == null || task.isBlank()) {
      throw new IllegalArgumentException("task must not be null or blank");
    }

    PromptVersion version = promptCatalogue.versionOf("planner");
    List<ChatMessage> messages =
        promptCatalogue.build("planner", renderUserContent(task, allowedTools));
    ChatOptions options = new ChatOptions(null, null, OUTPUT_SCHEMA);
    ChatRequest request = new ChatRequest(modelId, messages, options, version);

    ChatResponse response = port.chat(request);
    if (response.content() == null || response.content().isBlank()) {
      throw new PlannerPlanParseException(
          "planner returned no content (a refusal or an empty completion)", null);
    }
    PlannerPlan plan = parser.parse(response.content());
    PlannerPlanDto dto = PlannerPlanDto.from(plan);
    return new PlannerPlanResult(dto, response.model(), version, response.usage());
  }

  /**
   * The caller-injected user content the Planner proposes for: a header of the allowed-tool list
   * (so the model references only a tool the backend gave it) followed by the task text. This is
   * untrusted prompt-injection surface; it never contains the policy, which {@link
   * PromptCataloguePort#build} renders as a separate {@code SYSTEM} message.
   *
   * @param task the task text
   * @param allowedTools the allowed tool names (may be {@code null} / empty)
   * @return the user content string
   */
  private static String renderUserContent(String task, List<String> allowedTools) {
    String tools =
        (allowedTools == null || allowedTools.isEmpty())
            ? "(none)"
            : String.join(", ", allowedTools);
    return """
        Allowed tools: %s

        %s
        """
        .formatted(tools, task);
  }
}
