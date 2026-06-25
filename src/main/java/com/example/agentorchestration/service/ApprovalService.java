package com.example.agentorchestration.service;

import com.example.agentorchestration.dto.*;
import com.example.agentorchestration.persistence.ApprovalEntity;
import com.example.agentorchestration.persistence.ApprovalMapper;
import com.example.agentorchestration.persistence.ApprovalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovalService {

    private final ApprovalRepository repository;
    private final ApprovalMapper mapper;

    public ApprovalService(ApprovalRepository repository, ApprovalMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public ApprovalRecord create(AgentRoute route,
                                 String action,
                                 String target,
                                 String plannerReason,
                                 String draft,
                                 List<WorkerResult> evidence) {
        ApprovalEntity entity = new ApprovalEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setStatus(ApprovalStatus.PENDING);
        entity.setRoute(route);
        entity.setAction(action);
        entity.setTarget(target);
        entity.setPlannerReason(plannerReason);
        entity.setDraft(draft);
        entity.setEvidenceJson(mapper.toJson(evidence));
        entity.setCreatedAt(Instant.now());
        return mapper.toRecord(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public ApprovalRecord get(String id) {
        return repository.findById(id).map(mapper::toRecord)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + id));
    }

    @Transactional
    public ApprovalRecord decide(String id, boolean approve, String approver, String comments) {
        ApprovalEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + id));
        entity.setStatus(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        entity.setApprover(approver);
        entity.setComments(comments);
        return mapper.toRecord(repository.save(entity));
    }
}
