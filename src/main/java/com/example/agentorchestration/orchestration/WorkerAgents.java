package com.example.agentorchestration.orchestration;

import com.example.agentorchestration.dto.AgentRequest;
import com.example.agentorchestration.dto.WorkerResult;
import com.example.agentorchestration.tools.KnowledgeTools;
import com.example.agentorchestration.tools.OpsTools;
import com.example.agentorchestration.tools.UtilityTools;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class WorkerAgents {

    private final ChatClient chatClient;
    private final OpsTools opsTools;
    private final KnowledgeTools knowledgeTools;
    private final UtilityTools utilityTools;
    private final ObjectProvider<ToolCallbackProvider> mcpToolsProvider;
    private final TaskExecutor taskExecutor;
    private final ObservationRegistry observationRegistry;
    private final int timeoutSeconds;

    public WorkerAgents(ChatClient chatClient,
                        OpsTools opsTools,
                        KnowledgeTools knowledgeTools,
                        UtilityTools utilityTools,
                        ObjectProvider<ToolCallbackProvider> mcpToolsProvider,
                        TaskExecutor taskExecutor,
                        ObservationRegistry observationRegistry,
                        @Value("${app.workers.timeout-seconds:20}") int timeoutSeconds) {
        this.chatClient = chatClient;
        this.opsTools = opsTools;
        this.knowledgeTools = knowledgeTools;
        this.utilityTools = utilityTools;
        this.mcpToolsProvider = mcpToolsProvider;
        this.taskExecutor = taskExecutor;
        this.observationRegistry = observationRegistry;
        this.timeoutSeconds = timeoutSeconds;
    }

    public CompletableFuture<WorkerResult> opsHealth(String message) {
        return guarded("ops-health", () -> chatClient.prompt()
                .system("You are an operations health analyst. Use local ops tools to gather health facts and summarize briefly.")
                .tools(opsTools)
                .user(message)
                .call()
                .content());
    }

    public CompletableFuture<WorkerResult> opsRunbook(String message) {
        return guarded("ops-runbook", () -> chatClient.prompt()
                .system("You are a remediation specialist. Use knowledge tools to find runbook guidance and similar incidents.")
                .tools(knowledgeTools)
                .user(message)
                .call()
                .content());
    }

    public CompletableFuture<WorkerResult> opsMcp(String message) {
        return guarded("ops-mcp", () -> {
            ToolCallbackProvider mcpTools = mcpToolsProvider.getIfAvailable();
            if (mcpTools == null) {
                return "MCP tools are not configured. Skipping external evidence collection.";
            }
            return chatClient.prompt()
                    .system("You are an external evidence analyst. Use MCP tools if available and return a short evidence summary.")
                    .tools(mcpTools)
                    .user(message)
                    .call()
                    .content();
        });
    }

    public CompletableFuture<WorkerResult> support(String message) {
        return guarded("support", () -> chatClient.prompt()
                .system("You are a support specialist. Use knowledge tools to summarize issue, likely cause, and next actions.")
                .tools(knowledgeTools)
                .user(message)
                .call()
                .content());
    }

    public CompletableFuture<WorkerResult> general(AgentRequest request) {
        return guarded("general", () -> chatClient.prompt()
                .system("You are a general assistant. Use utility tools only if useful.")
                .tools(utilityTools)
                .user(request.message())
                .call()
                .content());
    }

    private CompletableFuture<WorkerResult> guarded(String workerName, ContentSupplier supplier) {
        return CompletableFuture.supplyAsync(() -> Observation.createNotStarted("agent.worker", observationRegistry)
                        .lowCardinalityKeyValue("worker", workerName)
                        .observe(() -> new WorkerResult(workerName, supplier.get())), taskExecutor)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> new WorkerResult(workerName, "Worker failed or timed out: " + ex.getMessage()));
    }

    @FunctionalInterface
    interface ContentSupplier {
        String get();
    }
}
