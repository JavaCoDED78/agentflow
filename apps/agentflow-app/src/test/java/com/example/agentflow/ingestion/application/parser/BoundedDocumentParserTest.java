package com.example.agentflow.ingestion.application.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.domain.ExtractionError;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the S14-10 wall-clock bounded parser: the real Tika/PDFBox engine is CPU-bound and
 * does not check interrupts, so the wrapper cannot stop a runaway parse — it stops the <i>wait</i>
 * and reports a typed failure instead. The boundedness is the whole point of the class, so the
 * elapsed-time pin is measured at the call site (generous margin — the assertion is about the bound
 * being respected at all, never a tight timing claim) and the timeout pin is proven with an
 * artificially hanging delegate rather than a real slow PDF.
 */
class BoundedDocumentParserTest {

  private BoundedDocumentParser parser;

  @AfterEach
  void tearDown() {
    if (parser != null) {
      parser.close();
      parser = null;
    }
  }

  @Test
  @DisplayName("a null delegate is rejected by the constructor")
  void constructor_rejects_null_delegate() {
    assertThatThrownBy(() -> new BoundedDocumentParser(null, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a null or non-positive bound is rejected by the constructor")
  void constructor_rejects_non_positive_bound() {
    DocumentParser delegate = mock(DocumentParser.class);

    assertThatThrownBy(() -> new BoundedDocumentParser(delegate, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedDocumentParser(delegate, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedDocumentParser(delegate, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a bounded, fast extraction passes through unchanged")
  void pass_through_returns_the_delegate_result() {
    DocumentParser delegate = mock(DocumentParser.class);
    ExtractionResult expected = ExtractionResult.of(1, "hello", Map.of(), false, "tika:test");
    when(delegate.parse("a.pdf", "application/pdf", new byte[] {1})).thenReturn(expected);

    parser = new BoundedDocumentParser(delegate, Duration.ofSeconds(5));
    ExtractionResult actual = parser.parse("a.pdf", "application/pdf", new byte[] {1});

    assertThat(actual).isSameAs(expected);
    assertThat(actual.status()).isEqualTo(ExtractionResult.Status.EXTRACTED);
    verify(delegate, times(1)).parse(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("a runaway extraction is abandoned at the bound and reported as TIMEOUT")
  void timeout_is_reported_as_a_typed_failure() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    DocumentParser hanging =
        (name, contentType, payload) -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            // abandoned by the bounded wrapper; the thread exits on its own
          }
          return ExtractionResult.of(1, "too late", Map.of(), false, "tika:test");
        };

    parser = new BoundedDocumentParser(hanging, Duration.ofMillis(150));

    long start = System.nanoTime();
    ExtractionResult result = parser.parse("slow.pdf", "application/pdf", new byte[] {1});
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    // The whole point of the wrapper: the caller never waits longer than the bound.
    assertThat(elapsedMillis).isLessThan(150 + 1_000);
    assertThat(result.status()).isEqualTo(ExtractionResult.Status.FAILED);
    assertThat(result.isFailure()).isTrue();
    assertThat(result.error().code()).isEqualTo(ExtractionError.Code.TIMEOUT);
    assertThat(result.error().summary()).contains("150ms");
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.pageCount()).isEqualTo(0);

    release.countDown();
  }

  @Test
  @DisplayName("a delegate exception propagates instead of being swallowed")
  void exception_from_delegate_propagates() {
    DocumentParser delegate = mock(DocumentParser.class);
    when(delegate.parse(anyString(), anyString(), any(byte[].class)))
        .thenThrow(new IllegalStateException("engine blew up"));

    parser = new BoundedDocumentParser(delegate, Duration.ofSeconds(5));

    assertThatThrownBy(() -> parser.parse("a.pdf", "application/pdf", new byte[] {1}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("engine blew up");
  }
}
