package com.example.agentorchestration.dto;

import java.util.List;

public record AgentResponse(
        AgentStatus status,
        AgentRoute route,
        String plannerReason,
        List<WorkerResult> workerResults,
        String finalAnswer,
        String approvalId,
        String approvalAction
) {
}
