package com.example.agentflow.messaging.application;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test support: an in-memory {@link SdkTracerProvider} whose finished spans are recorded into a
 * list, so a unit/integration test can assert the consumer-side span contract of the messaging
 * boundary (S13-10) without a broker, a database or an OTLP collector.
 *
 * <p>The exporter retains every finished {@link SpanData}; {@link Fixture#spans()} reads them and
 * {@link Fixture#close()} stops the provider.
 */
public final class RecordingSpanExporter implements SpanExporter {

  private final List<SpanData> spans = new CopyOnWriteArrayList<>();

  @Override
  public CompletableResultCode export(Collection<SpanData> spansToExport) {
    spans.addAll(spansToExport);
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  /** The fixture returned by {@link #fixture()}: the provider plus the recorded spans. */
  public static final class Fixture implements AutoCloseable {
    public final SdkTracerProvider provider;
    private final List<SpanData> spans;

    private Fixture(SdkTracerProvider provider, List<SpanData> spans) {
      this.provider = provider;
      this.spans = spans;
    }

    /**
     * @return the finished spans recorded so far (finish order).
     */
    public List<SpanData> spans() {
      return List.copyOf(spans);
    }

    /** Stop the provider (and the processor attached to the exporter). */
    @Override
    public void close() {
      provider.close();
    }
  }

  /**
   * Build a fixture: an in-memory {@link SdkTracerProvider} plus the list all finished spans land
   * in.
   *
   * <p>The exporter is retained by the {@link Fixture}; {@link Fixture#close()} stops the provider.
   *
   * @return the fixture (provider + recorded spans)
   */
  public static Fixture fixture() {
    RecordingSpanExporter exporter = new RecordingSpanExporter();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    return new Fixture(provider, exporter.spans);
  }
}
