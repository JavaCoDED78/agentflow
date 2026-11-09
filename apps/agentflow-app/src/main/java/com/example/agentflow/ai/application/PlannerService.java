package com.example.agentflow.ai.application;

import com.example.agentflow.ai.domain.plan.PlannerPlan;
import java.util.List;
import java.util.Objects;
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
 *
 * <p><b>Bounded repair.</b> A plan the model returns is untrusted content: if it is not well-formed
 * JSON, not a valid plan, or is refused, that is a clear fault, never a silent {@code null} plan.
 * {@code S09-08} classifies it ({@link PlannerFailure#isRepairable()}) and repairs a repairable
 * failure with at most one corrective retry, only when budget is available — so a malformed plan
 * never drives an infinite loop of model calls.
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

  /** The provider port; optional, so the app boots without a configured provider. */
  @Autowired(required = false)
  private final ChatModelPort chatModelPort;

  /**
   * The bounded repair policy (S09-08): how many corrective retries a malformed-plan attempt may
   * take. It defaults to one — a single second chance, never an infinite loop of model calls — and
   * is configurable through {@code agentflow.ai.planner.retry.max-retries}.
   */
  private final PlannerRepairPolicy repairPolicy;

  /**
   * The pre-call model budget guard (S09-10): it reserves cost before a call and settles it after.
   * It is injected {@code @Autowired(required = false)} following the module's existing opt-in,
   * loud-at-call-time pattern (the same one the {@link #chatModelPort} uses): a missing bean means
   * "no budget guard", so the Planner works standalone and a real budget from {@code usage} is
   * wired in later (S12). It is evaluated before every model call, so an exhausted budget stops the
   * call before the request is sent (S09-10 acceptance).
   */
  @Autowired(required = false)
  private final ModelBudget budget;

  /**
   * @param promptCatalogue the prompt catalogue (always available); loads the planner policy
   * @param modelId the model the adapter targets by default (see the class comment)
   * @param maxRetries the maximum number of corrective retries a malformed-plan attempt may take
   *     (S09-08); a value of {@code 0} means "never retry"
   * @param chatModelPort the provider port; optional, so the app boots without a configured
   *     provider
   * @param budget the optional model budget guard; {@code null} means "no budget guard"
   */
  @Autowired
  public PlannerService(
      PromptCataloguePort promptCatalogue,
      @Value("${agentflow.ai.provider.openai.model:gpt-4o-mini}") String modelId,
      @Value("${agentflow.ai.planner.retry.max-retries:1}") int maxRetries,
      @Autowired(required = false) ChatModelPort chatModelPort,
      @Autowired(required = false) ModelBudget budget) {
    this(promptCatalogue, modelId, chatModelPort, PlannerRepairPolicy.of(maxRetries), budget);
  }

  /**
   * @param promptCatalogue the prompt catalogue (always available); loads the planner policy
   * @param modelId the model the adapter targets by default (see the class comment)
   * @param chatModelPort the provider port; optional, so the app boots without a configured
   *     provider
   * @param repairPolicy the bounded repair policy (S09-08); never {@code null}
   * @param budget the optional model budget guard; {@code null} means "no budget guard"
   */
  public PlannerService(
      PromptCataloguePort promptCatalogue,
      String modelId,
      ChatModelPort chatModelPort,
      PlannerRepairPolicy repairPolicy,
      ModelBudget budget) {
    this.promptCatalogue = promptCatalogue;
    this.modelId = modelId;
    this.chatModelPort = chatModelPort;
    this.repairPolicy = Objects.requireNonNull(repairPolicy, "repairPolicy must not be null");
    this.budget = budget;
  }

  /**
   * Convenience constructor: the bounded repair policy is the default ({@link
   * PlannerRepairPolicy#ofDefaults()}), so callers vary only the provider and the optional budget
   * check (used by the unit tests).
   *
   * @param promptCatalogue the prompt catalogue (always available); loads the planner policy
   * @param modelId the model the adapter targets by default (see the class comment)
   * @param chatModelPort the provider port; optional, so the app boots without a configured
   *     provider
   * @param budget the optional model budget guard; {@code null} means "no budget guard"
   */
  public PlannerService(
      PromptCataloguePort promptCatalogue,
      String modelId,
      ChatModelPort chatModelPort,
      ModelBudget budget) {
    this(promptCatalogue, modelId, chatModelPort, PlannerRepairPolicy.ofDefaults(), budget);
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
   * @throws PlannerPlanParseException if the model returned no content, returned content that is
   *     not a valid plan, or the provider call itself failed; its {@link
   *     PlannerPlanParseException#classification()} (S09-08) explains why
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

    // A malformed or refused plan is repaired with a bounded corrective retry (S09-08): the model
    // is called at least once, and — only for a repairable failure, and at most
    // repairPolicy.maxRetries() times — called again. The last allowed retry's failure is
    // terminal, so a malformed plan never drives an infinite loop of model calls. The pre-call
    // model budget guard (S09-10) is evaluated before EVERY attempt (initial call included): the
    // budget is reserved before the request is sent and throws InsufficientBudgetException when
    // exhausted, so an already-committed budget stops the next attempt too — a budget spent by the
    // initial call blocks the retry.
    for (int attempt = 0; ; attempt++) {
      ModelBudgetReservation reservation = budget == null ? null : budget.reserve();
      try {
        PlannerPlanResult result = proposeAttempt(port, task, allowedTools);
        if (reservation != null) {
          // Post-call settlement (S09-10): the real cost is not metered yet (price catalogue is
          // S11), so settle(null) leaves the reservation committed unchanged — a no-op this sprint.
          // The reserve-before-the-call (above) is what blocks an exhausted budget.
          reservation.settle(null);
        }
        return result;
      } catch (PlannerPlanParseException e) {
        // A failed attempt keeps its reservation, so a budget spent by it blocks the next
        // attempt (S09-10) — the guard is what makes the S09-08 "budget consumed by the initial
        // call blocks the retry" hold.
        if (!retryIfPossible(e, attempt)) {
          throw e;
        }
      }
    }
  }

  /**
   * One attempt to propose a plan: build the request, call the port, then turn the response into a
   * plan. Fails loud and clear on a malformed or refused plan — never returns a silent {@code null}
   * plan — carrying a {@link PlannerFailure} classification the bounded-repair policy (S09-08)
   * uses.
   *
   * @param port the provider port (already checked non-null in the caller)
   * @param task the caller's task text (the untrusted user content the Planner proposes for)
   * @param allowedTools the tool names the Planner may reference (the allowlist the backend gave
   *     it); {@code null} / empty means no tool references
   * @return the structured-plan result
   * @throws PlannerPlanParseException the attempt produced no usable plan; its {@link
   *     PlannerPlanParseException#classification()} classifies the failure
   */
  private PlannerPlanResult proposeAttempt(
      ChatModelPort port, String task, List<String> allowedTools) {
    PromptVersion version = promptCatalogue.versionOf("planner");
    List<ChatMessage> messages =
        promptCatalogue.build("planner", renderUserContent(task, allowedTools));
    ChatOptions options = new ChatOptions(null, null, OUTPUT_SCHEMA);
    ChatRequest request = new ChatRequest(modelId, messages, options, version);

    ChatResponse response;
    try {
      response = port.chat(request);
    } catch (PlannerPlanParseException e) {
      // A malformed / schema-mismatch plan from a prior repair attempt — it already carries its
      // classification, so reclassify nothing and let the bounded-repair policy decide.
      throw e;
    } catch (RuntimeException e) {
      // The provider call itself failed (network / timeout / server error) before a response was
      // produced; a transport failure the bounded-repair policy may retry (S09-08).
      throw new PlannerPlanParseException(
          "planner call failed: " + e.getMessage(), PlannerFailure.TRANSPORT_FAILURE, e);
    }
    if (response.content() == null || response.content().isBlank()) {
      throw new PlannerPlanParseException(
          "planner returned no content (a refusal or an empty completion)",
          PlannerFailure.PROVIDER_REFUSAL,
          null);
    }
    PlannerPlan plan = parser.parse(response.content());
    PlannerPlanDto dto = PlannerPlanDto.from(plan);
    return new PlannerPlanResult(dto, response.model(), version, response.usage());
  }

  /**
   * Decide whether a failed attempt may be corrected with another model call. A retry is allowed
   * only when all hold: the failure is {@link PlannerFailure#isRepairable() repairable}, and the
   * policy still allows it (fewer than {@code maxRetries} retries have run). The pre-call budget
   * guard (S09-10) is evaluated before EVERY attempt in {@code propose} — a budget exhausted by the
   * initial call blocks the retry there, not here.
   *
   * @param failure the failed attempt's failure (never {@code null}); its classification is the
   *     repair gate
   * @param attempt the zero-based attempt that just failed ({@code 0} is the initial call)
   * @return {@code true} if a corrective retry is allowed (so {@code propose} loops); {@code false}
   *     if the failure is terminal (so {@code propose} rethrows it)
   */
  private boolean retryIfPossible(PlannerPlanParseException failure, int attempt) {
    if (!failure.classification().isRepairable()) {
      return false;
    }
    // attempt 0 is the initial call; a retry is allowed for attempts 0..maxRetries()-1, so a policy
    // of 1 allows exactly one retry after the initial call.
    if (attempt >= repairPolicy.maxRetries()) {
      return false;
    }
    return true;
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
