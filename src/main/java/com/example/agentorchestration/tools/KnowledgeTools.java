package com.example.agentorchestration.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeTools {

    @Tool(description = "Search remediation runbooks for a topic or service")
    public String searchRunbook(@ToolParam(description = "Topic, service or symptom") String topic) {
        return "Runbook for '%s': check active deployment, inspect logs, verify downstream connectivity, confirm no schema drift, and only restart after approval with rollback readiness.".formatted(topic);
    }

    @Tool(description = "Search similar incidents for a service")
    public String searchRecentIncidents(@ToolParam(description = "Service name") String serviceName) {
        return "Recent incidents for '%s': two similar degradations in the last 30 days linked to connection pool exhaustion after deployment and one caused by downstream throttling.".formatted(serviceName);
    }
}
