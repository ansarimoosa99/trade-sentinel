package com.example.tradesentinel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "order_events")
@Getter @Setter @NoArgsConstructor
public class OrderEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "trader_id", nullable = false)
    private String traderId;

    @Column(name = "account_id")
    private String accountId;

    @Column(nullable = false)
    private String symbol;

    private String exchange;

    @Column(nullable = false)
    private String side;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal price;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "ingested_at")
    private Instant ingestedAt;

    @PrePersist
    void prePersist() {
        if (ingestedAt == null) ingestedAt = Instant.now();
    }
}
