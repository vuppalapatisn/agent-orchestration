package com.example.agentorchestration.dto;

public record RoutePlan(
        AgentRoute route,
        String reason,
        int confidence,
        boolean approvalRequired,
        String approvalAction
) {
}
