package com.example.tradesentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "watchlist_entries")
@Getter @Setter @NoArgsConstructor
public class WatchlistEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trader_id", unique = true, nullable = false)
    private String traderId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore = 0;

    @Column(name = "alert_count", nullable = false)
    private int alertCount = 0;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "added_reason", columnDefinition = "TEXT")
    private String addedReason;

    @Column(name = "added_at")
    private Instant addedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (addedAt == null) addedAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "ACTIVE";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
