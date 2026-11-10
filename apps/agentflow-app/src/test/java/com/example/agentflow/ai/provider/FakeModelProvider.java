package com.example.agentflow.ai.provider;

import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ChatUsage;
import com.openai.errors.OpenAIServiceException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The deterministic fake model provider (S09-11): a {@link ChatModelPort} that reproduces every
 * provider outcome the platform must survive, without any API key, network or clock dependency, so
 * the standard {@code ./gradlew check} gate exercises the Planner (and, later, the AI step handler)
 * against fixed fixtures instead of a live model.
 *
 * <p>It lives in the <em>test</em> source set (the S04-10 pattern: the permissive security chain
 * and this fake are compiled into no production jar), so a misconfigured profile can never point a
 * real deployment at it. The wiring that exposes it is {@link FakeModelConfiguration}, opt-in
 * through {@code agentflow.ai.provider=fake} and never set by the default, local or kubernetes
 * profiles.
 *
 * <p>One fixture per provider behaviour, each fully deterministic — the same request always
 * produces the same response, the same thrown fault, or the same refusal:
 *
 * <ul>
 *   <li>{@link Fixture#VALID_PLAN} — a fixed, structurally valid two-step plan (a {@code TOOL_CALL}
 *       followed by a {@code FINALIZE} that depends on it); parses through the real {@code
 *       PlannerPlanParser} into a {@code PlannerPlan};
 *   <li>{@link Fixture#INVALID_PLAN} — well-formed JSON that is not a structurally valid plan (a
 *       duplicated step id), so the parser classifies it {@code SCHEMA_MISMATCH};
 *   <li>{@link Fixture#MALFORMED_RESPONSE} — content that is not JSON at all, so the parser
 *       classifies it {@code MALFORMED_JSON};
 *   <li>{@link Fixture#TIMEOUT} — the call fails the way a read timeout does: the thrown fault
 *       carries a {@link TimeoutException} cause, so the production {@code
 *       ProviderExceptionClassifier} maps it to {@code TIMEOUT} (retryable) and the Planner maps it
 *       to {@code TRANSPORT_FAILURE} (repairable);
 *   <li>{@link Fixture#RATE_LIMIT} — the call fails the way an HTTP 429 does: the thrown fault is
 *       an {@link OpenAIServiceException} with status code 429, so the production classifier maps
 *       it to {@code RATE_LIMIT} (retryable) and the Planner maps it to {@code TRANSPORT_FAILURE};
 *   <li>{@link Fixture#REFUSAL} — a completion with {@code null} content and a {@code
 *       content_filter} finish reason, the shape the port contract defines for a refusal.
 * </ul>
 *
 * <p>The fault fixtures deliberately reuse the production fault <em>types</em> ({@link
 * TimeoutException} on the cause chain, a 429 {@link OpenAIServiceException}) rather than inventing
 * parallel fake ones, so a fixture that flows through {@code ResilientChatModel} is classified by
 * the real {@code ProviderExceptionClassifier} exactly as a live provider fault would be. Every
 * call is counted ({@link #calls()}) so bounded-repair tests can assert the exact number of model
 * calls, and no time, randomness or I/O is read (invariant 10).
 */
public final class FakeModelProvider implements ChatModelPort {

  /** The model label every fake response carries; a fixture, never a real model id. */
  public static final String MODEL = "fake-model";

  /** The fixed token accounting every content-bearing fixture reports. */
  private static final ChatUsage USAGE = new ChatUsage(12, 6, 18);

  /** The {@link Fixture#VALID_PLAN} completion: a tool call followed by a finalize on it. */
  static final String VALID_PLAN_JSON =
      "{\"steps\":[{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"calculator\","
          + "\"arguments\":{\"expression\":\"2+2\"}},"
          + "{\"id\":\"s2\",\"type\":\"FINALIZE\",\"dependsOn\":[\"s1\"]}]}";

  /** The {@link Fixture#INVALID_PLAN} completion: well-formed JSON, duplicated step id. */
  static final String INVALID_PLAN_JSON =
      "{\"steps\":[{\"id\":\"dup\",\"type\":\"FINALIZE\"},{\"id\":\"dup\",\"type\":\"FINALIZE\"}]}";

  /** The {@link Fixture#MALFORMED_RESPONSE} completion: not JSON at all. */
  static final String MALFORMED_JSON = "{ not valid json";

  private final Fixture fixture;
  private final AtomicInteger calls = new AtomicInteger();

  /**
   * @param fixture the behaviour every {@link #chat(ChatRequest)} call reproduces; must not be
   *     {@code null}
   * @throws IllegalArgumentException if {@code fixture} is {@code null}
   */
  public FakeModelProvider(Fixture fixture) {
    if (fixture == null) {
      throw new IllegalArgumentException("fixture must not be null");
    }
    this.fixture = fixture;
  }

  /**
   * Convenience factory for the fixture that answers every call with a valid plan.
   *
   * @return a {@link Fixture#VALID_PLAN} fake provider
   */
  public static FakeModelProvider validPlan() {
    return new FakeModelProvider(Fixture.VALID_PLAN);
  }

  /**
   * @return the behaviour this provider reproduces on every call
   */
  public Fixture fixture() {
    return fixture;
  }

  /**
   * @return how many {@link #chat(ChatRequest)} calls have run; the bounded-repair evidence that a
   *     Planner retry loop made exactly as many model calls as the policy allows
   */
  public int calls() {
    return calls.get();
  }

  /**
   * Reproduce the configured fixture. The request is already validated by {@link ChatRequest}'s
   * canonical constructor, so the fake never re-validates it; every call is counted.
   *
   * @param request the request to answer (ignored except for the call count)
   * @return the fixture's response; {@code null} content on the refusal fixture
   * @throws RuntimeException the fixture's fault (timeout / rate limit) when it models a failed
   *     call
   */
  @Override
  public ChatResponse chat(ChatRequest request) {
    calls.incrementAndGet();
    return switch (fixture) {
      case VALID_PLAN -> new ChatResponse(MODEL, VALID_PLAN_JSON, "stop", USAGE);
      case INVALID_PLAN -> new ChatResponse(MODEL, INVALID_PLAN_JSON, "stop", USAGE);
      case MALFORMED_RESPONSE -> new ChatResponse(MODEL, MALFORMED_JSON, null, USAGE);
      case TIMEOUT ->
          throw new IllegalStateException(
              "fake provider read timeout", new TimeoutException("fake provider timed out"));
      case RATE_LIMIT -> throw rateLimited();
      case REFUSAL -> new ChatResponse(MODEL, null, "content_filter", null);
    };
  }

  /**
   * The 429 fault, shaped exactly like the OpenAI SDK's generic service exception with status code
   * 429 (the abstract base's constructor is {@code protected}, so an anonymous subclass builds it —
   * the same technique {@code ProviderExceptionClassifierTest} uses). The production classifier
   * therefore maps this fixture to {@code RATE_LIMIT}, not {@code UNKNOWN}.
   *
   * @return a thrown-never-returned 429 service fault
   */
  private static RuntimeException rateLimited() {
    return new OpenAIServiceException("fake provider 429", null) {
      @Override
      public int statusCode() {
        return 429;
      }

      @Override
      public com.openai.core.http.Headers headers() {
        return null;
      }

      @Override
      public com.openai.core.JsonValue body() {
        return null;
      }

      @Override
      public Optional<String> code() {
        return Optional.empty();
      }

      @Override
      public Optional<String> param() {
        return Optional.empty();
      }

      @Override
      public Optional<String> type() {
        return Optional.empty();
      }
    };
  }

  /**
   * The deterministic behaviour set the fake provider reproduces (S09-11): one enum constant per
   * provider outcome the platform must survive.
   */
  public enum Fixture {

    /** Answer every call with a fixed, structurally valid two-step plan. */
    VALID_PLAN,

    /** Answer every call with well-formed JSON that is not a structurally valid plan. */
    INVALID_PLAN,

    /** Answer every call with content that is not JSON at all. */
    MALFORMED_RESPONSE,

    /** Fail every call the way a read timeout does (a {@link TimeoutException} cause chain). */
    TIMEOUT,

    /** Fail every call the way an HTTP 429 does (a 429 service fault). */
    RATE_LIMIT,

    /** Answer every call with a refusal: {@code null} content and a {@code content_filter} stop. */
    REFUSAL;

    /**
     * Resolve a configuration value (for example {@code agentflow.ai.provider.fake.mode}) to a
     * fixture. Kebab-case names are accepted case-insensitively ({@code valid-plan}), and {@code
     * 429} is accepted as an alias for {@link #RATE_LIMIT}; blank means the documented default,
     * {@link #VALID_PLAN}. An unknown value is a configuration error and fails fast with the list
     * of supported modes.
     *
     * @param mode the configured mode; {@code null} or blank selects {@link #VALID_PLAN}
     * @return the fixture for {@code mode}
     * @throws IllegalArgumentException if {@code mode} names no fixture
     */
    public static Fixture fromConfig(String mode) {
      if (mode == null || mode.isBlank()) {
        return VALID_PLAN;
      }
      String normalized = mode.trim().replace('-', '_').toUpperCase(Locale.ROOT);
      if ("429".equals(normalized)) {
        return RATE_LIMIT;
      }
      try {
        return valueOf(normalized);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "unknown fake provider mode '"
                + mode
                + "'; expected one of: valid-plan, invalid-plan, malformed-response, timeout,"
                + " 429 (rate limit), refusal",
            e);
      }
    }
  }
}
