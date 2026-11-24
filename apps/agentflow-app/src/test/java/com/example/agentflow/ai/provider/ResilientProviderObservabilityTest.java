package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ChatUsage;
import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ai.application.EmbeddingUsage;
import com.example.agentflow.ai.application.MessageRole;
import com.example.agentflow.ai.application.ProviderFailureReason;
import com.example.agentflow.ai.application.ProviderResiliencePolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the S13-13 observability surface on the resilient provider wrappers.
 *
 * <p>Every assertion is DB-free and time-trivial (injected sleeper/jitter, fixed policy): the
 * wrapper records the S13-13 meters (latency / errors / tokens) against a real {@link
 * SimpleMeterRegistry}, and — when a {@link Tracer} is wired — a closed {@code ...#chat} / {@code
 * ...#embed} span with a single {@code agentflow.ai.model} attribute. The span is asserted through
 * an in-memory {@link io.opentelemetry.sdk.trace.SdkTracerProvider} fixture (the same seam as the
 * messaging S13-10 tests) so the span contract is proven without a broker, a collector or an
 * exporter. Absence of the registry/tracer is also proven: the call completes normally.
 */
class ResilientProviderObservabilityTest {

  private static final String MODEL = "gpt-4o-mini";
  private static final String EMBED_MODEL = "nomic-embed-text";

  private final ExecutorService callExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "test-resilient-obs");
            thread.setDaemon(true);
            return thread;
          });

  @AfterEach
  void shutDownExecutor() {
    callExecutor.shutdownNow();
  }

  // ---- chat ----------------------------------------------------------------------------------

  @Test
  void chat_records_input_and_output_tokens_on_a_clean_success() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeChatPort delegate =
        new FakeChatPort(new ChatResponse(MODEL, "ok", "stop", new ChatUsage(7, 3, 10)));

    resilientChat(delegate, registry, null).chat(request());

    assertThat(registry.find("agentflow.ai.provider.chat.tokens.input.total").counter().count())
        .isEqualTo(7.0);
    assertThat(registry.find("agentflow.ai.provider.chat.tokens.output.total").counter().count())
        .isEqualTo(3.0);
  }

  @Test
  void chat_records_the_latency_timer_and_no_tokens_on_a_failure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeChatPort delegate = new FakeChatPort(null).failWith(new PermanentFault());

    assertThatThrownBy(() -> resilientChat(delegate, registry, null).chat(request()))
        .isInstanceOf(PermanentFault.class);

    assertThat(registry.find("agentflow.ai.provider.chat.latency.seconds").timer()).isNotNull();
    // the failure is recorded once (the single attempt — no retry from a permanent fault)
    assertThat(
            registry
                .find("agentflow.ai.provider.chat.errors.total")
                .tag("reason", "client_error")
                .counter()
                .count())
        .isEqualTo(1.0);
    // a failed call records no tokens (never an invented value)
    assertThat(registry.find("agentflow.ai.provider.chat.tokens.input.total").meters()).isEmpty();
  }

  @Test
  void chat_starts_a_span_with_only_the_model_attribute() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingSpanFixture spanFixture = RecordingSpanFixture.fixture();
    try {
      FakeChatPort delegate = new FakeChatPort(new ChatResponse(MODEL, "ok", "stop", null));
      resilientChat(delegate, registry, spanFixture.tracer()).chat(request());

      List<io.opentelemetry.sdk.trace.data.SpanData> spans = spanFixture.spans();
      assertThat(spans).hasSize(1);
      assertThat(spans.get(0).getName()).isEqualTo("ResilientChatModel#chat");
      assertThat(
              spans
                  .get(0)
                  .getAttributes()
                  .get(io.opentelemetry.api.common.AttributeKey.stringKey("agentflow.ai.model")))
          .isEqualTo(MODEL);
      // no other span attributes (no tenant, no prompt, no identifiers)
      assertThat(spans.get(0).getAttributes().size()).isEqualTo(1);
    } finally {
      spanFixture.close();
    }
  }

  @Test
  void chat_without_the_registry_completes_normally() {
    FakeChatPort delegate = new FakeChatPort(new ChatResponse(MODEL, "ok", "stop", null));
    ChatResponse response = resilientChat(delegate, null, null).chat(request());

    assertThat(response.content()).isEqualTo("ok");
  }

  // ---- embedding -------------------------------------------------------------------------------

  @Test
  void embedding_records_tokens_on_a_clean_success() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeEmbeddingPort delegate =
        new FakeEmbeddingPort(
            new EmbeddingResponse(
                EMBED_MODEL,
                List.of(new float[] {1.0f, 2.0f}, new float[] {3.0f, 4.0f}),
                new EmbeddingUsage(5, 2)));

    resilientEmbedding(delegate, registry, null).embed(embeddingRequest());

    assertThat(registry.find("agentflow.ai.provider.embedding.tokens.total").counter().count())
        .isEqualTo(5.0);
  }

  @Test
  void embedding_records_the_latency_timer_on_a_failure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeEmbeddingPort delegate = new FakeEmbeddingPort(null).failWith(new TransientFault());

    assertThatThrownBy(() -> resilientEmbedding(delegate, registry, null).embed(embeddingRequest()))
        .isInstanceOf(RuntimeException.class);

    assertThat(registry.find("agentflow.ai.provider.embedding.latency.seconds").timer())
        .isNotNull();
  }

  // ---- builders ---------------------------------------------------------------------------------

  private static ChatRequest request() {
    return new ChatRequest(MODEL, List.of(new ChatMessage(MessageRole.USER, "hi")));
  }

  private static EmbeddingRequest embeddingRequest() {
    return new EmbeddingRequest(EMBED_MODEL, List.of("alpha", "beta"));
  }

  private ResilientChatModel resilientChat(
      FakeChatPort delegate, SimpleMeterRegistry registry, Tracer tracer) {
    return new ResilientChatModel(
        delegate,
        ProviderResiliencePolicy.of(1, Duration.ofMillis(1), 2.0, Duration.ofMillis(5), false),
        classifyTransient(),
        Duration.ofSeconds(30),
        4,
        null,
        noopSleeper(),
        callExecutor,
        registry,
        tracer);
  }

  private ResilientEmbeddingModel resilientEmbedding(
      FakeEmbeddingPort delegate, SimpleMeterRegistry registry, Tracer tracer) {
    return new ResilientEmbeddingModel(
        delegate,
        ProviderResiliencePolicy.of(1, Duration.ofMillis(1), 2.0, Duration.ofMillis(5), false),
        classifyTransient(),
        Duration.ofSeconds(30),
        4,
        null,
        noopSleeper(),
        callExecutor,
        registry,
        tracer);
  }

  private static Function<Throwable, ProviderFailureReason> classifyTransient() {
    return fault ->
        fault instanceof TransientFault
            ? ProviderFailureReason.RATE_LIMIT
            : ProviderFailureReason.CLIENT_ERROR;
  }

  private static Consumer<Long> noopSleeper() {
    return delayMillis -> {};
  }

  // ---- fakes ------------------------------------------------------------------------------------

  /** A transient, retryable fault the fake chat delegate throws. */
  private static final class TransientFault extends RuntimeException {
    TransientFault() {
      super("transient provider fault");
    }
  }

  /** A permanent, non-retryable fault the fake chat delegate throws. */
  private static final class PermanentFault extends RuntimeException {
    PermanentFault() {
      super("permanent provider fault");
    }
  }

  private static final class FakeChatPort implements ChatModelPort {
    ChatResponse response;
    List<RuntimeException> perCallFailures = new ArrayList<>();
    volatile int calls = 0;

    FakeChatPort(ChatResponse response) {
      this.response = response;
    }

    FakeChatPort failWith(RuntimeException failure) {
      perCallFailures.add(failure);
      return this;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
      calls++;
      int index = calls - 1;
      if (index < perCallFailures.size()) {
        throw perCallFailures.get(index);
      }
      return response;
    }
  }

  private static final class FakeEmbeddingPort implements EmbeddingPort {
    EmbeddingResponse response;
    List<RuntimeException> perCallFailures = new ArrayList<>();
    volatile int calls = 0;

    FakeEmbeddingPort(EmbeddingResponse response) {
      this.response = response;
    }

    FakeEmbeddingPort failWith(RuntimeException failure) {
      perCallFailures.add(failure);
      return this;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
      calls++;
      int index = calls - 1;
      if (index < perCallFailures.size()) {
        throw perCallFailures.get(index);
      }
      return response;
    }
  }

  /** In-memory recording tracer fixture (mirrors the messaging S13-10 seam). */
  private static final class RecordingSpanFixture implements AutoCloseable {
    private final io.opentelemetry.sdk.trace.SdkTracerProvider provider;
    private List<io.opentelemetry.sdk.trace.data.SpanData> spans;

    private RecordingSpanFixture(io.opentelemetry.sdk.trace.SdkTracerProvider provider) {
      this.provider = provider;
    }

    static RecordingSpanFixture fixture() {
      RecordingSpanExporter exporter = new RecordingSpanExporter();
      io.opentelemetry.sdk.trace.SdkTracerProvider provider =
          io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
              .addSpanProcessor(
                  io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter))
              .build();
      RecordingSpanFixture fixture = new RecordingSpanFixture(provider);
      fixture.spans = exporter.spans;
      return fixture;
    }

    Tracer tracer() {
      return provider.get("com.example.agentflow.ai.provider");
    }

    List<io.opentelemetry.sdk.trace.data.SpanData> spans() {
      return List.copyOf(spans);
    }

    @Override
    public void close() {
      provider.close();
    }

    private static final class RecordingSpanExporter
        implements io.opentelemetry.sdk.trace.export.SpanExporter {
      final List<io.opentelemetry.sdk.trace.data.SpanData> spans =
          new java.util.concurrent.CopyOnWriteArrayList<>();

      @Override
      public io.opentelemetry.sdk.common.CompletableResultCode export(
          java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData> spansToExport) {
        spans.addAll(spansToExport);
        return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
      }

      @Override
      public io.opentelemetry.sdk.common.CompletableResultCode flush() {
        return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
      }

      @Override
      public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
        return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
      }
    }
  }
}
