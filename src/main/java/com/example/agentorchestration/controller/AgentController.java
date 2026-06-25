package com.example.agentorchestration.controller;

import com.example.agentorchestration.dto.AgentRequest;
import com.example.agentorchestration.dto.AgentResponse;
import com.example.agentorchestration.dto.ApprovalDecisionRequest;
import com.example.agentorchestration.dto.ApprovalRecord;
import com.example.agentorchestration.orchestration.AgentOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents")
@Tag(name = "Agent Orchestration", description = "Multi-agent orchestration endpoints")
public class AgentController {

    private final AgentOrchestratorService orchestratorService;

    public AgentController(AgentOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @Operation(summary = "Execute an agent request", description = "Routes the request to the appropriate worker agents and returns the orchestrated response")
    @PostMapping("/execute")
    public ResponseEntity<AgentResponse> execute(@Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(orchestratorService.orchestrate(request));
    }

    @Operation(summary = "Get approval record", description = "Retrieves a pending approval record by its ID")
    @GetMapping("/approvals/{approvalId}")
    public ResponseEntity<ApprovalRecord> getApproval(
            @Parameter(description = "Approval record ID") @PathVariable String approvalId) {
        return ResponseEntity.ok(orchestratorService.getApproval(approvalId));
    }

    @Operation(summary = "Submit approval decision", description = "Approve or reject a pending action and execute it if approved")
    @PostMapping("/approvals/{approvalId}/decision")
    public ResponseEntity<AgentResponse> decide(
            @Parameter(description = "Approval record ID") @PathVariable String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return ResponseEntity.ok(orchestratorService.decideApproval(approvalId, request));
    }
}
