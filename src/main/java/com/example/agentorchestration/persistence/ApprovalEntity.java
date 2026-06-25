package com.example.agentorchestration.persistence;

import com.example.agentorchestration.dto.AgentRoute;
import com.example.agentorchestration.dto.ApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "approval_requests")
public class ApprovalEntity {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentRoute route;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String target;

    @Column(nullable = false, length = 2000)
    private String plannerReason;

    @Lob
    @Column(nullable = false)
    private String draft;

    @Lob
    @Column(nullable = false)
    private String evidenceJson;

    @Column(nullable = false)
    private Instant createdAt;

    private String approver;

    @Column(length = 2000)
    private String comments;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public ApprovalStatus getStatus() { return status; }
    public void setStatus(ApprovalStatus status) { this.status = status; }
    public AgentRoute getRoute() { return route; }
    public void setRoute(AgentRoute route) { this.route = route; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getPlannerReason() { return plannerReason; }
    public void setPlannerReason(String plannerReason) { this.plannerReason = plannerReason; }
    public String getDraft() { return draft; }
    public void setDraft(String draft) { this.draft = draft; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getApprover() { return approver; }
    public void setApprover(String approver) { this.approver = approver; }
    public String getComments() { return comments; }
    public void setComments(String comments) { this.comments = comments; }
}
