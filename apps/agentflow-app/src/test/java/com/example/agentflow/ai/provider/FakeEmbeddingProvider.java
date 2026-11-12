package com.example.agentflow.ai.provider;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ai.application.EmbeddingUsage;
import com.openai.errors.OpenAIServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The deterministic fake embedding provider (S10-12): an {@link EmbeddingPort} that produces
 * reproducible 768-dimensional vectors — and reproduces the provider faults the embedding pipeline
 * must survive — without any API key, network or clock dependency, so {@code ./gradlew check}
 * exercises the embedding generation service (batching, ordering, validation) against fixed
 * fixtures instead of a live model. This is the S10-12 acceptance: the embeddings pipeline is
 * verified without a real model.
 *
 * <p>It lives in the <em>test</em> source set, exactly like {@link FakeModelProvider} (the S09-11 /
 * S04-10 pattern: the fake is compiled into no production jar), so a misconfigured profile can
 * never point a real deployment at it. The wiring that exposes it is {@link
 * FakeModelConfiguration}, opt-in through {@code agentflow.ai.provider=fake} and never set by the
 * default, local or kubernetes profiles.
 *
 * <p>One fixture per provider behaviour, each fully deterministic:
 *
 * <ul>
 *   <li>{@link Fixture#DETERMINISTIC} — one vector per input text, every vector derived from the
 *       SHA-256 of the text (the {@code nomic-embed-text}-pinning {@code vector(768)} width), so
 *       the same text always yields the same vector, two different texts (almost surely) yield
 *       different vectors, and the output order always matches the input order;
 *   <li>{@link Fixture#RATE_LIMIT} — the call fails the way an HTTP 429 does: a 429 {@link
 *       OpenAIServiceException}, which the production {@code ProviderExceptionClassifier} maps to
 *       {@code RATE_LIMIT} (retryable), so it flows through {@code ResilientEmbeddingModel} exactly
 *       like a live provider rate limit;
 *   <li>{@link Fixture#TIMEOUT} — the call fails the way a read timeout does: a {@link
 *       TimeoutException} on the cause chain, classified {@code TIMEOUT} (retryable) by the
 *       production classifier.
 * </ul>
 *
 * <p>The fault fixtures deliberately reuse the production fault <em>types</em> (a 429 {@link
 * OpenAIServiceException}, a {@link TimeoutException} cause) rather than inventing parallel fake
 * ones, so a fixture that flows through {@code ResilientEmbeddingModel} is classified by the real
 * classifier exactly as a live provider fault would be. Every call is counted ({@link #calls()}) so
 * retry tests can assert the exact number of provider calls, and no time, randomness or I/O is read
 * (invariant 10).
 */
public final class FakeEmbeddingProvider implements EmbeddingPort {

  /** The model label every fake response carries; a fixture, never a real model id. */
  public static final String MODEL = "fake-embedding-model";

  /** The embedding width, pinned to the {@code vector(768)} the S10-11 migration stores. */
  public static final int DIMENSIONS = 768;

  private final Fixture fixture;
  private final AtomicInteger calls = new AtomicInteger();

  /**
   * @param fixture the behaviour every {@link #embed(EmbeddingRequest)} call reproduces; must not
   *     be {@code null}
   * @throws IllegalArgumentException if {@code fixture} is {@code null}
   */
  public FakeEmbeddingProvider(Fixture fixture) {
    if (fixture == null) {
      throw new IllegalArgumentException("fixture must not be null");
    }
    this.fixture = fixture;
  }

  /**
   * Convenience factory for the fixture that answers every call with deterministic vectors.
   *
   * @return a {@link Fixture#DETERMINISTIC} fake provider
   */
  public static FakeEmbeddingProvider deterministic() {
    return new FakeEmbeddingProvider(Fixture.DETERMINISTIC);
  }

  /**
   * @return the behaviour this provider reproduces on every call
   */
  public Fixture fixture() {
    return fixture;
  }

  /**
   * @return how many {@link #embed(EmbeddingRequest)} calls have run; the evidence that a retry
   *     loop made exactly as many provider calls as the policy allows
   */
  public int calls() {
    return calls.get();
  }

  /**
   * Reproduce the configured fixture. The request is already validated by {@link
   * EmbeddingRequest}'s canonical constructor, so the fake never re-validates it; every call is
   * counted.
   *
   * @param request the request to answer (the texts drive the deterministic vectors; the model is
   *     echoed back)
   * @return one deterministic vector per input text, in order, with the fixed width {@link
   *     #DIMENSIONS}
   * @throws RuntimeException the fixture's fault (rate limit / timeout) when it models a failed
   *     call
   */
  @Override
  public EmbeddingResponse embed(EmbeddingRequest request) {
    calls.incrementAndGet();
    return switch (fixture) {
      case DETERMINISTIC -> deterministicResponse(request);
      case RATE_LIMIT -> throw rateLimited();
      case TIMEOUT ->
          throw new IllegalStateException(
              "fake embedding read timeout", new TimeoutException("fake embedding timed out"));
    };
  }

  /**
   * Build the deterministic response: one {@link #DETERMINISTIC}-width vector per text, each
   * derived from the SHA-256 digest of the text repeated over the vector slots, so the same text
   * always produces the same vector. Token accounting is a deterministic estimate (one token per
   * four characters, the standard rule of thumb), never a random value.
   *
   * @param request the request to answer
   * @return the deterministic response
   */
  private static EmbeddingResponse deterministicResponse(EmbeddingRequest request) {
    List<float[]> embeddings = new ArrayList<>(request.texts().size());
    long tokens = 0L;
    for (String text : request.texts()) {
      embeddings.add(vectorFor(text));
      tokens += Math.max(1, text.length() / 4);
    }
    return new EmbeddingResponse(
        request.model(), embeddings, new EmbeddingUsage(tokens, DIMENSIONS));
  }

  /**
   * Derive the {@link #DIMENSIONS}-wide vector of one text: the SHA-256 digest of {@code text +
   * counter} fills the vector four bytes at a time until the width is reached, every value mapped
   * into {@code [0, 1)}. Pure and reproducible — no clock, no randomness.
   *
   * @param text the text to derive the vector from
   * @return the deterministic embedding vector
   */
  private static float[] vectorFor(String text) {
    float[] vector = new float[DIMENSIONS];
    MessageDigest digest = newSha256();
    for (int offset = 0; offset < DIMENSIONS; offset += 8) {
      digest.reset();
      digest.update(text.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) (offset & 0xFF));
      digest.update((byte) ((offset >> 8) & 0xFF));
      byte[] hash = digest.digest();
      for (int i = 0; i < 8 && offset + i < DIMENSIONS; i++) {
        // Map every byte into [0, 1); the absolute values do not matter, only the determinism.
        vector[offset + i] = (hash[i] & 0xFF) / 256.0f;
      }
    }
    return vector;
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JDK specification; this can never happen on a compliant VM.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * The 429 fault, shaped exactly like the OpenAI SDK's generic service exception with status code
   * 429 (the abstract base's constructor is {@code protected}, so an anonymous subclass builds it —
   * the same technique {@link FakeModelProvider} uses). The production classifier therefore maps
   * this fixture to {@code RATE_LIMIT}, not {@code UNKNOWN}.
   *
   * @return a thrown-never-returned 429 service fault
   */
  private static RuntimeException rateLimited() {
    return new OpenAIServiceException("fake embedding provider 429", null) {
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
   * The deterministic behaviour set the fake embedding provider reproduces (S10-12): one enum
   * constant per provider outcome the embedding pipeline must survive.
   */
  public enum Fixture {

    /** Answer every call with one deterministic {@link #DIMENSIONS}-wide vector per input text. */
    DETERMINISTIC,

    /** Fail every call the way an HTTP 429 does (a 429 service fault). */
    RATE_LIMIT,

    /** Fail every call the way a read timeout does (a {@link TimeoutException} cause chain). */
    TIMEOUT;

    /**
     * Resolve a configuration value (for example {@code agentflow.ai.provider.fake.embedding-mode})
     * to a fixture. Kebab-case names are accepted case-insensitively ({@code rate-limit}); blank
     * means the documented default, {@link #DETERMINISTIC}. An unknown value is a configuration
     * error and fails fast with the list of supported modes.
     *
     * @param mode the configured mode; {@code null} or blank selects {@link #DETERMINISTIC}
     * @return the fixture for {@code mode}
     * @throws IllegalArgumentException if {@code mode} names no fixture
     */
    public static Fixture fromConfig(String mode) {
      if (mode == null || mode.isBlank()) {
        return DETERMINISTIC;
      }
      String normalized = mode.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
      if ("429".equals(normalized)) {
        return RATE_LIMIT;
      }
      try {
        return valueOf(normalized);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "unknown fake embedding mode '"
                + mode
                + "'; expected one of: deterministic, rate-limit, 429, timeout",
            e);
      }
    }
  }
}
