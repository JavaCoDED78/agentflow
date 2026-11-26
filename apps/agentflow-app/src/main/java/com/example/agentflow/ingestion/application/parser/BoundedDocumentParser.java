package com.example.agentflow.ingestion.application.parser;

import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.domain.ExtractionError;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A wall-clock bounded {@link DocumentParser} (S14-10): it wraps the real, synchronous extraction
 * engine and guarantees the caller never waits longer than the configured bound for a result.
 *
 * <p>The real work (PDFBox / Tika) is CPU-bound and does not check interrupts, so a timeout cannot
 * stop the underlying parse — it stops the <i>wait</i>: the caller returns a {@link
 * ExtractionResult.Status#FAILED} result carrying {@link ExtractionError.Code#TIMEOUT}, and the
 * abandoned daemon thread may keep running to completion (it holds no locks and the parser is
 * stateless, so a runaway thread is bounded by the payload size and the page / character caps the
 * engine already enforces). No document is indexable on a timeout path.
 *
 * <p>The bound is mandatory and positive, and lives in configuration ({@code
 * agentflow.ingestion.max-extraction-seconds}) — a nonsensical bound fails construction at context
 * startup, exactly like the {@code max-pages} / {@code max-chars} bounds. The daemon threads are
 * cached and self-expire, mirroring the {@code ai} resilience layer's posture ({@code
 * ResilientChatModel} / {@code ResilientEmbeddingModel}); the wrapper is a singleton, so its
 * executor is shut down by Spring on context close.
 */
public final class BoundedDocumentParser implements DocumentParser {

  /**
   * Label reported as the extraction engine version on a timeout failure (never the delegate's).
   */
  private static final String TIMEOUT_PARSER_VERSION = "bounded-document-parser";

  private final DocumentParser delegate;
  private final Duration overallTimeout;
  private final ExecutorService executor;

  /**
   * @param delegate the real extraction port (non-null); never {@code null}
   * @param overallTimeout the wall-clock bound for a single extraction (must be positive)
   */
  public BoundedDocumentParser(DocumentParser delegate, Duration overallTimeout) {
    this(delegate, overallTimeout, Executors.newCachedThreadPool(daemonFactory()));
  }

  /**
   * @param delegate the real extraction port (non-null)
   * @param overallTimeout the wall-clock bound for a single extraction (must be positive)
   * @param executor the executor running the bounded attempt (non-null); injected so tests can
   *     supply a deterministic one
   */
  BoundedDocumentParser(
      DocumentParser delegate, Duration overallTimeout, ExecutorService executor) {
    if (delegate == null) {
      throw new IllegalArgumentException("delegate must not be null");
    }
    if (overallTimeout == null || overallTimeout.isZero() || overallTimeout.isNegative()) {
      throw new IllegalArgumentException("overallTimeout must be positive, was " + overallTimeout);
    }
    if (executor == null) {
      throw new IllegalArgumentException("executor must not be null");
    }
    this.delegate = delegate;
    this.overallTimeout = overallTimeout;
    this.executor = executor;
  }

  @Override
  public ExtractionResult parse(String originalName, String contentType, byte[] payload) {
    Future<ExtractionResult> future =
        executor.submit(() -> delegate.parse(originalName, contentType, payload));
    try {
      return future.get(overallTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      // The parse is abandoned, not stopped: the bounded wait is the guarantee, the engine may
      // keep running on its daemon thread (it is stateless and holds no locks).
      future.cancel(true);
      return ExtractionResult.failed(
          new ExtractionError(
              ExtractionError.Code.TIMEOUT,
              "extraction exceeded the time limit of " + formatLimit(overallTimeout)),
          TIMEOUT_PARSER_VERSION);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new RuntimeException("bounded document extraction interrupted", interrupted);
    } catch (ExecutionException thrown) {
      Throwable cause = thrown.getCause() != null ? thrown.getCause() : thrown;
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new RuntimeException(cause);
    }
  }

  /**
   * Shut the executor down; invoked by Spring when the managed bean is destroyed so the daemon
   * threads do not linger past the context (mirrors the {@code ai} resilience layer).
   */
  @PreDestroy
  public void close() {
    executor.shutdown();
  }

  private static ThreadFactory daemonFactory() {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "bounded-document-parser-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /** Render a bound for the safe summary: whole seconds when that is the intent, else millis. */
  private static String formatLimit(Duration d) {
    long millis = d.toMillis();
    if (millis >= 1000 && millis % 1000 == 0) {
      return (millis / 1000) + "s";
    }
    return millis + "ms";
  }
}
