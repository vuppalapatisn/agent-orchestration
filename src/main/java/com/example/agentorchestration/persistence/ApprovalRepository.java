package com.example.agentorchestration.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<ApprovalEntity, String> {
}
