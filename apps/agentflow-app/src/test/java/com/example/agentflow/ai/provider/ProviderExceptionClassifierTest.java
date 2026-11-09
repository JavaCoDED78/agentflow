package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.ai.application.ProviderFailureReason;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnprocessableEntityException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the provider exception-to-failure-reason classifier (S09-09).
 *
 * <p>The classifier is the {@code ai} module's only owner of the OpenAI SDK's exception hierarchy,
 * so these pin the {@link ProviderFailureReason} every fault class resolves to: {@code 429} /
 * {@code 5xx} are retryable ({@link ProviderFailureReason#RATE_LIMIT} / {@link
 * ProviderFailureReason#SERVER_ERROR}), transport-level faults map to {@link
 * ProviderFailureReason#CONNECTION}, timeouts to {@link ProviderFailureReason#TIMEOUT}, permanent
 * {@code 4xx} and unrecognised faults to {@link ProviderFailureReason#CLIENT_ERROR} / {@link
 * ProviderFailureReason#UNKNOWN}. The classifier also walks the whole cause chain, so an SDK fault
 * hidden inside a wrapper is still classified. DB-free (no network / model / clock); the SDK types
 * come from the provider adapter's dependency, already on the test classpath.
 */
class ProviderExceptionClassifierTest {

  private final ProviderExceptionClassifier classifier = new ProviderExceptionClassifier();

  /**
   * A concrete {@link OpenAIServiceException} with a controllable status code. The abstract service
   * exception's constructor is {@code protected}, so a test in this package builds it directly and
   * overrides only {@link #statusCode()} (the classifier's decision key) — this exercises the
   * generic status-code branch exactly as the resilient wrapper would observe a generic service
   * fault.
   */
  private static OpenAIServiceException serviceException(int statusCode) {
    return new OpenAIServiceException("service fault", null) {
      @Override
      public int statusCode() {
        return statusCode;
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

  @Test
  void a_429_service_exception_is_rate_limited() {
    assertThat(classifier.classify(serviceException(429)))
        .isEqualTo(ProviderFailureReason.RATE_LIMIT);
  }

  @Test
  void a_5xx_service_exception_is_server_error() {
    assertThat(classifier.classify(serviceException(503)))
        .isEqualTo(ProviderFailureReason.SERVER_ERROR);
  }

  @Test
  void a_4xx_service_exception_is_a_permanent_client_error() {
    assertThat(classifier.classify(serviceException(400)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
    assertThat(classifier.classify(serviceException(403)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
    assertThat(classifier.classify(serviceException(422)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
  }

  @Test
  void a_rate_limit_exception_is_rate_limited_before_the_generic_service_branch() {
    RateLimitException rateLimited = mock(RateLimitException.class);
    when(rateLimited.statusCode()).thenReturn(429);
    assertThat(classifier.classify(rateLimited)).isEqualTo(ProviderFailureReason.RATE_LIMIT);
  }

  @Test
  void an_internal_server_exception_is_server_error_before_the_generic_service_branch() {
    InternalServerException server = mock(InternalServerException.class);
    when(server.statusCode()).thenReturn(500);
    assertThat(classifier.classify(server)).isEqualTo(ProviderFailureReason.SERVER_ERROR);
  }

  @Test
  void a_bad_request_exception_is_a_permanent_client_error() {
    assertThat(classifier.classify(mock(BadRequestException.class)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
  }

  @Test
  void the_authenticity_failures_are_permanent_client_errors() {
    assertThat(classifier.classify(mock(UnauthorizedException.class)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
    assertThat(classifier.classify(mock(PermissionDeniedException.class)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
    assertThat(classifier.classify(mock(NotFoundException.class)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
    assertThat(classifier.classify(mock(UnprocessableEntityException.class)))
        .isEqualTo(ProviderFailureReason.CLIENT_ERROR);
  }

  @Test
  void an_io_exception_is_a_connection_fault() {
    assertThat(classifier.classify(new OpenAIIoException("connection reset")))
        .isEqualTo(ProviderFailureReason.CONNECTION);
  }

  @Test
  void a_future_timeout_is_a_timeout() {
    assertThat(classifier.classify(new TimeoutException()))
        .isEqualTo(ProviderFailureReason.TIMEOUT);
  }

  @Test
  void a_socket_timeout_is_a_timeout() {
    assertThat(classifier.classify(new SocketTimeoutException()))
        .isEqualTo(ProviderFailureReason.TIMEOUT);
  }

  @Test
  void a_resilient_wrapper_timeout_is_a_timeout() {
    assertThat(
            classifier.classify(
                new ResilientChatModel.ProviderTimeoutException(Duration.ofSeconds(5), null)))
        .isEqualTo(ProviderFailureReason.TIMEOUT);
  }

  @Test
  void a_plain_openai_exception_is_unknown_and_not_retryable() {
    assertThat(classifier.classify(new OpenAIException("unclassified")))
        .isEqualTo(ProviderFailureReason.UNKNOWN);
  }

  @Test
  void an_unrecognised_runtime_exception_is_unknown() {
    assertThat(classifier.classify(new IllegalStateException("nope")))
        .isEqualTo(ProviderFailureReason.UNKNOWN);
  }

  @Test
  void an_unrecognised_checked_exception_is_unknown() {
    assertThat(classifier.classify(new IllegalStateException("nope")))
        .isEqualTo(ProviderFailureReason.UNKNOWN);
    assertThat(classifier.classify(new Exception("nope"))).isEqualTo(ProviderFailureReason.UNKNOWN);
  }

  @Test
  void the_classifier_walks_the_cause_chain_to_a_hidden_service_fault() {
    Throwable wrapped = new RuntimeException("adapter wrapper", serviceException(500));
    assertThat(classifier.classify(wrapped)).isEqualTo(ProviderFailureReason.SERVER_ERROR);
  }

  @Test
  void a_hidden_rate_limit_inside_a_wrapper_is_still_rate_limited() {
    RateLimitException rateLimited = mock(RateLimitException.class);
    when(rateLimited.statusCode()).thenReturn(429);
    assertThat(classifier.classify(new RuntimeException("wrapper", rateLimited)))
        .isEqualTo(ProviderFailureReason.RATE_LIMIT);
  }
}
