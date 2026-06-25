package com.example.agentorchestration.dto;

import java.time.Instant;
import java.util.List;

public record ApprovalRecord(
        String id,
        ApprovalStatus status,
        AgentRoute route,
        String action,
        String target,
        String plannerReason,
        String draft,
        List<WorkerResult> evidence,
        Instant createdAt,
        String approver,
        String comments
) {
}
