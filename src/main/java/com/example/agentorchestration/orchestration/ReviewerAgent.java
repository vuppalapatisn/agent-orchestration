package com.example.agentorchestration.orchestration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class ReviewerAgent {

    private final ChatClient chatClient;

    public ReviewerAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String review(String draft) {
        String systemPrompt = """
                You are a reviewer agent.
                Improve clarity and preserve facts.
                Produce concise, executive-friendly output with:
                1. Executive Summary
                2. Evidence
                3. Recommendation
                4. Risk and Approval
                """;

        return chatClient.prompt()
                .system(systemPrompt)
                .user(draft)
                .call()
                .content();
    }
}
