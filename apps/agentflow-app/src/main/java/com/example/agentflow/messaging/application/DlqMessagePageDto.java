package com.example.agentflow.messaging.application;

import java.util.List;

/**
 * A single page of {@link DlqMessageDto} returned by the DLQ inspection list endpoint (S12-11),
 * together with the pagination metadata a client needs to page through the store without loading it
 * all (the "no unbounded collection" criterion — API invariant 11).
 *
 * <p>The {@code items} are the overview records (metadata only, no failure text); the metadata
 * ({@code page}, {@code size}, {@code totalElements}, {@code totalPages}, {@code hasNext}, {@code
 * hasPrevious}) describes the current window within the whole, deterministically ordered ({@code
 * recorded_at DESC, id DESC}) set.
 */
public record DlqMessagePageDto(
    List<DlqMessageDto> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext,
    boolean hasPrevious) {}
