package com.example.agentorchestration.persistence;

import com.example.agentorchestration.dto.ApprovalRecord;
import com.example.agentorchestration.dto.WorkerResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApprovalMapper {

    private final ObjectMapper objectMapper;

    public ApprovalMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApprovalRecord toRecord(ApprovalEntity entity) {
        try {
            List<WorkerResult> evidence = objectMapper.readValue(entity.getEvidenceJson(), new TypeReference<>() {});
            return new ApprovalRecord(
                    entity.getId(),
                    entity.getStatus(),
                    entity.getRoute(),
                    entity.getAction(),
                    entity.getTarget(),
                    entity.getPlannerReason(),
                    entity.getDraft(),
                    evidence,
                    entity.getCreatedAt(),
                    entity.getApprover(),
                    entity.getComments()
            );
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize approval evidence", e);
        }
    }

    public String toJson(List<WorkerResult> workerResults) {
        try {
            return objectMapper.writeValueAsString(workerResults);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to serialize worker results", e);
        }
    }
}
