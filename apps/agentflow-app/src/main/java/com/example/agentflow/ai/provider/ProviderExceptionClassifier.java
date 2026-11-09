package com.example.agentflow.ai.provider;

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
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Maps a thrown exception from a provider call to a provider failure reason (S09-09).
 *
 * <p>This is the only code in the {@code ai} module that understands the OpenAI SDK's exception
 * hierarchy, so it is the single owner of the SDK-specific part of the failure classification (the
 * {@link ProviderFailureReason} enum itself is neutral and lives in {@code ai.application}). The
 * resilient wrapper ({@code ResilientChatModel}) delegates to this classifier through the {@link
 * #classify(Throwable)} function to decide whether to retry.
 *
 * <p><b>Cause-chain walk.</b> The provider call may surface the SDK exception directly, or Spring
 * AI may wrap it inside an intermediate runtime exception, so the classifier walks the whole cause
 * chain and returns the first reason it can justify. More specific SDK types are matched before the
 * generic {@link OpenAIServiceException} (whose status code is inspected) so that, for example, a
 * {@link RateLimitException} is recognised as {@link ProviderFailureReason#RATE_LIMIT} rather than
 * a generic 429.
 *
 * <p><b>Transport and timeout.</b> {@link SocketTimeoutException}/{@link TimeoutException} (whether
 * from the SDK or raised by the resilient wrapper's overall-timeout abort) map to {@link
 * ProviderFailureReason#TIMEOUT}; a {@link OpenAIIoException} maps to {@link
 * ProviderFailureReason#CONNECTION}.
 *
 * <p>The classifier is stateless, so it is a Spring {@link Component} and can also be used as a
 * plain function ({@code classifier::classify}) without a container.
 */
@Component
public class ProviderExceptionClassifier {

  /**
   * @param failure the exception thrown by a provider call (or an exception that wraps one)
   * @return the provider failure reason; {@link ProviderFailureReason#UNKNOWN} when nothing on the
   *     cause chain matches a known fault, so the resilient wrapper fails the call instead of
   *     retrying blindly
   */
  public ProviderFailureReason classify(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      ProviderFailureReason reason = reasonFor(current);
      if (reason != null) {
        return reason;
      }
    }
    return ProviderFailureReason.UNKNOWN;
  }

  /**
   * @param t an exception found on the cause chain (or the root cause)
   * @return a concrete reason for {@code t}, or {@code null} when it is not a recognised provider
   *     fault (so the caller keeps walking the cause chain)
   */
  private static ProviderFailureReason reasonFor(Throwable t) {
    // The specific 429 / 5xx types are matched before the generic service exception.
    if (t instanceof ResilientChatModel.ProviderTimeoutException) {
      return ProviderFailureReason.TIMEOUT;
    }
    if (t instanceof RateLimitException) {
      return ProviderFailureReason.RATE_LIMIT;
    }
    if (t instanceof InternalServerException) {
      return ProviderFailureReason.SERVER_ERROR;
    }
    if (t instanceof OpenAIServiceException service) {
      int status = service.statusCode();
      if (status == 429) {
        return ProviderFailureReason.RATE_LIMIT;
      }
      if (status >= 500) {
        return ProviderFailureReason.SERVER_ERROR;
      }
      // Any other service status (4xx such as 400/401/403/404/422) is a permanent client error.
      return ProviderFailureReason.CLIENT_ERROR;
    }
    if (t instanceof OpenAIIoException) {
      return ProviderFailureReason.CONNECTION;
    }
    if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
      return ProviderFailureReason.TIMEOUT;
    }
    // Permanent client errors: a malformed or rejected request is not worth retrying.
    if (isClientError(t)) {
      return ProviderFailureReason.CLIENT_ERROR;
    }
    // A recognised OpenAI exception that is none of the above is unknown to this classifier.
    if (t instanceof OpenAIException) {
      return ProviderFailureReason.UNKNOWN;
    }
    return null;
  }

  /**
   * @param t an exception on the cause chain
   * @return {@code true} if {@code t} is a permanent 4xx-style client error the SDK models
   *     explicitly (bad request, unauthorized, permission denied, not found, unprocessable entity)
   */
  private static boolean isClientError(Throwable t) {
    return t instanceof BadRequestException
        || t instanceof UnauthorizedException
        || t instanceof PermissionDeniedException
        || t instanceof NotFoundException
        || t instanceof UnprocessableEntityException;
  }
}
