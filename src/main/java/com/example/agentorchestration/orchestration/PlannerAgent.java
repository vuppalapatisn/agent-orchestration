package com.example.agentorchestration.orchestration;

import com.example.agentorchestration.dto.RoutePlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class PlannerAgent {

    private final ChatClient chatClient;

    public PlannerAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public RoutePlan plan(String userMessage) {
        String systemPrompt = """
                You are a planner agent for an enterprise operations assistant.
                Routes:
                - OPS: health check, restart, deployment, CPU, memory, latency, pods, infra
                - SUPPORT: incident summary, RCA hints, KB, troubleshooting without risky actions
                - GENERAL: everything else

                Rules:
                - approvalRequired must be true for restart, rollback, stop, reboot, delete, scale-down, or production-impacting change.
                - approvalAction should be concise like RESTART_SERVICE or NONE.
                - confidence must be between 0 and 100.

                Return strict JSON with fields:
                route, reason, confidence, approvalRequired, approvalAction
                """;

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .entity(RoutePlan.class);
    }
}
