package com.example.agentflow.ai.application;

/**
 * Token accounting for a {@link ChatResponse}.
 *
 * <p>All values are non-negative counts. The {@link #totalTokens()} field is a convenience for
 * usage metering (S11); the provider reports input and output tokens and the adapter or the usage
 * service derives the total. Cost is never carried here — it is a {@code NUMERIC}/{@code Decimal}
 * value owned by the {@code usage} module, never a floating-point estimate in the contract.
 */
public record ChatUsage(long inputTokens, long outputTokens, long totalTokens) {}
