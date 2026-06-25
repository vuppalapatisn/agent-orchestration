package com.example.agentorchestration.dto;

import jakarta.validation.constraints.NotBlank;

public record ApprovalDecisionRequest(
        boolean approve,
        @NotBlank String approver,
        String comments
) {
}
