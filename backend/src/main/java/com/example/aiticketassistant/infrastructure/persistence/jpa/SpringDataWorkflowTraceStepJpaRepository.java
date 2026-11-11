package com.example.aiticketassistant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataWorkflowTraceStepJpaRepository extends JpaRepository<WorkflowTraceStepJpaEntity, Long> {}
