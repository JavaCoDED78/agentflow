package com.example.agentflow.workflowdefinition.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/workflows} (S03-13).
 *
 * <p>The {@code name} is required and unique per tenant; {@code description} is optional free text.
 * Bean validation ({@code @Valid} on the controller parameter) turns a blank {@code name} into a
 * {@code 400} validation Problem Details via the global error handler.
 */
public record CreateWorkflowDefinitionRequest(
    @NotBlank(message = "name is required") String name, String description) {}
