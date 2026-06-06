package com.example.tradesentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "compliance_cases")
@Getter @Setter @NoArgsConstructor
public class ComplianceCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", unique = true, nullable = false)
    private String caseId;

    @Column(name = "alert_id", nullable = false)
    private String alertId;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "assigned_to")
    private String assignedTo;

    private String priority;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "OPEN";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
