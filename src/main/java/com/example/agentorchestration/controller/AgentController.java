package com.example.agentorchestration.controller;

import com.example.agentorchestration.dto.AgentRequest;
import com.example.agentorchestration.dto.AgentResponse;
import com.example.agentorchestration.dto.ApprovalDecisionRequest;
import com.example.agentorchestration.dto.ApprovalRecord;
import com.example.agentorchestration.orchestration.AgentOrchestratorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentOrchestratorService orchestratorService;

    public AgentController(AgentOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @PostMapping("/execute")
    public ResponseEntity<AgentResponse> execute(@Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(orchestratorService.orchestrate(request));
    }

    @GetMapping("/approvals/{approvalId}")
    public ResponseEntity<ApprovalRecord> getApproval(@PathVariable String approvalId) {
        return ResponseEntity.ok(orchestratorService.getApproval(approvalId));
    }

    @PostMapping("/approvals/{approvalId}/decision")
    public ResponseEntity<AgentResponse> decide(@PathVariable String approvalId,
                                                @Valid @RequestBody ApprovalDecisionRequest request) {
        return ResponseEntity.ok(orchestratorService.decideApproval(approvalId, request));
    }
}
