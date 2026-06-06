package com.example.tradesentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_events")
@Getter @Setter @NoArgsConstructor
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id")
    private String alertId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String actor = "SYSTEM";

    @Column(name = "event_time")
    private Instant eventTime;

    @PrePersist
    void prePersist() {
        if (eventTime == null) eventTime = Instant.now();
        if (actor == null) actor = "SYSTEM";
    }
}
