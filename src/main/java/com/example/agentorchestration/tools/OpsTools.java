package com.example.agentorchestration.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class OpsTools {

    @Tool(description = "Check health of a service and return CPU, memory, restart count and status")
    public String serviceHealth(@ToolParam(description = "Service name") String serviceName) {
        return "Service '%s' is DEGRADED. CPU 82%%, memory 71%%, restart count 3 in the last 15 minutes, latency p95 840 ms, error rate 4.2%%.".formatted(serviceName);
    }

    @Tool(description = "Restart a service in a controlled change-managed way")
    public String restartService(@ToolParam(description = "Service name") String serviceName) {
        return "Controlled restart executed for '%s'. Change record CR-2026-0042 approved. Post-check health is HEALTHY with error rate below 0.5%%.".formatted(serviceName);
    }
}
