package com.example.agentorchestration.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentRequest(
        @NotBlank String message,
        String userId,
        String conversationId
) {
}
