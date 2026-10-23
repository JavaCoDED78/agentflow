package com.example.agentflow.workflowdefinition.application;

import java.util.List;

/**
 * A single page of {@link WorkflowDefinitionDto} returned by the list endpoint (S03-14), together
 * with the pagination metadata a client needs to page through the collection without loading it all
 * (the "no unbounded collection" criterion).
 *
 * <p>This is the {@code workflowdefinition} module's public list DTO — the permitted type the
 * application layer exposes across the module boundary (see {@code
 * docs/architecture/module-api.md}). The {@code items} carry the full per-definition fields; the
 * metadata ({@code page}, {@code size}, {@code totalElements}, {@code totalPages}, {@code hasNext},
 * {@code hasPrevious}) describes the current window within the tenant's whole, deterministically
 * ordered set.
 */
public record WorkflowDefinitionPageDto(
    List<WorkflowDefinitionDto> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext,
    boolean hasPrevious) {}
