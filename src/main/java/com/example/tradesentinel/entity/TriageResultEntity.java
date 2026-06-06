package com.example.tradesentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "triage_results")
@Getter @Setter @NoArgsConstructor
public class TriageResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private String alertId;

    @Column(nullable = false)
    private String verdict;

    @Column(nullable = false)
    private int confidence;

    @Column(name = "fp_probability", nullable = false)
    private int fpProbability;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "case_note", columnDefinition = "TEXT")
    private String caseNote;

    @Column(nullable = false)
    private String source;

    @Column(name = "risk_factors", columnDefinition = "TEXT")
    private String riskFactors;

    @Column(name = "fp_factors", columnDefinition = "TEXT")
    private String fpFactors;

    @Column(name = "recommended_actions", columnDefinition = "TEXT")
    private String recommendedActions;

    @Column(name = "triaged_at")
    private Instant triagedAt;

    @PrePersist
    void prePersist() {
        if (triagedAt == null) triagedAt = Instant.now();
    }
}
