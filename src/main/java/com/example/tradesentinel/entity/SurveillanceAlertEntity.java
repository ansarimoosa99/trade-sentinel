package com.example.tradesentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "surveillance_alerts")
@Getter @Setter @NoArgsConstructor
public class SurveillanceAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", unique = true, nullable = false)
    private String alertId;

    @Column(nullable = false)
    private String pattern;

    @Column(name = "trader_id", nullable = false)
    private String traderId;

    @Column(name = "account_id")
    private String accountId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private int score;

    @Column(name = "risk_score")
    private int riskScore = 0;

    @Column(columnDefinition = "TEXT")
    private String metrics;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "OPEN";
    }
}
