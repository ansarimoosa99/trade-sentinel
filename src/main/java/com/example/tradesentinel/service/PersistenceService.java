package com.example.tradesentinel.service;

import com.example.tradesentinel.entity.*;
import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.OrderEvent;
import com.example.tradesentinel.model.TriageResult;
import com.example.tradesentinel.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersistenceService {

    private final OrderEventRepository orderEventRepository;
    private final SurveillanceAlertRepository alertRepository;
    private final TriageResultRepository triageResultRepository;
    private final ComplianceCaseRepository caseRepository;
    private final WatchlistEntryRepository watchlistRepository;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    // ── Order Events ──────────────────────────────────────────────────────────

    @Transactional
    public void saveOrderEvents(List<OrderEvent> events, String runId) {
        List<OrderEventEntity> entities = events.stream().map(e -> {
            OrderEventEntity entity = new OrderEventEntity();
            entity.setOrderId(e.orderId());
            entity.setTraderId(e.traderId());
            entity.setAccountId(e.accountId());
            entity.setSymbol(e.symbol());
            entity.setExchange(e.exchange());
            entity.setSide(e.side());
            entity.setQuantity(e.quantity());
            entity.setPrice(BigDecimal.valueOf(e.price()));
            entity.setEventType(e.eventType());
            entity.setEventTime(e.eventTime());
            entity.setRunId(runId);
            return entity;
        }).toList();
        orderEventRepository.saveAll(entities);
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    @Transactional
    public void saveAlert(Alert alert) {
        if (alertRepository.findByAlertId(alert.alertId()).isPresent()) return;
        SurveillanceAlertEntity entity = new SurveillanceAlertEntity();
        entity.setAlertId(alert.alertId());
        entity.setPattern(alert.pattern());
        entity.setTraderId(alert.traderId());
        entity.setAccountId(alert.accountId());
        entity.setSymbol(alert.symbol());
        entity.setSeverity(alert.severity());
        entity.setScore(alert.score());
        entity.setRiskScore(0);
        entity.setMetrics(toJson(alert.metrics()));
        entity.setStatus("OPEN");
        alertRepository.save(entity);
        audit(alert.alertId(), "ALERT_CREATED",
                "Alert %s detected: %s for trader %s".formatted(alert.alertId(), alert.pattern(), alert.traderId()));
    }

    @Transactional
    public void updateAlertRiskScore(String alertId, int riskScore) {
        alertRepository.findByAlertId(alertId).ifPresent(entity -> {
            entity.setRiskScore(riskScore);
            alertRepository.save(entity);
        });
    }

    // ── Triage ────────────────────────────────────────────────────────────────

    @Transactional
    public void saveTriage(String alertId, TriageResult triage) {
        if (triageResultRepository.findByAlertId(alertId).isPresent()) return;
        TriageResultEntity entity = new TriageResultEntity();
        entity.setAlertId(alertId);
        entity.setVerdict(triage.verdict());
        entity.setConfidence(triage.confidence());
        entity.setFpProbability(triage.falsePositiveProbability());
        entity.setReason(triage.reason());
        entity.setCaseNote(triage.caseNote());
        entity.setSource(triage.source());
        entity.setRiskFactors(toJson(triage.riskFactors()));
        entity.setFpFactors(toJson(triage.falsePositiveFactors()));
        entity.setRecommendedActions(toJson(triage.recommendedActions()));
        triageResultRepository.save(entity);
        audit(alertId, "TRIAGE_COMPLETE",
                "Verdict: %s (confidence %d%%) via %s".formatted(triage.verdict(), triage.confidence(), triage.source()));
    }

    // ── Compliance Cases ──────────────────────────────────────────────────────

    @Transactional
    public ComplianceCaseEntity createCase(String alertId, String priority, String assignedTo) {
        if (caseRepository.findByAlertId(alertId).isPresent()) {
            return caseRepository.findByAlertId(alertId).get();
        }
        ComplianceCaseEntity entity = new ComplianceCaseEntity();
        entity.setCaseId("CASE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        entity.setAlertId(alertId);
        entity.setStatus("OPEN");
        entity.setPriority(priority);
        entity.setAssignedTo(assignedTo);
        ComplianceCaseEntity saved = caseRepository.save(entity);
        audit(alertId, "CASE_CREATED", "Case %s created, priority %s, assigned to %s".formatted(saved.getCaseId(), priority, assignedTo));
        return saved;
    }

    // ── Watchlist ─────────────────────────────────────────────────────────────

    @Transactional
    public void addToWatchlist(String traderId, int riskScore, String reason) {
        WatchlistEntryEntity entry = watchlistRepository.findByTraderId(traderId)
                .orElseGet(() -> {
                    WatchlistEntryEntity e = new WatchlistEntryEntity();
                    e.setTraderId(traderId);
                    return e;
                });
        entry.setRiskScore(riskScore);
        entry.setAlertCount(entry.getAlertCount() + 1);
        entry.setStatus("ACTIVE");
        entry.setAddedReason(reason);
        watchlistRepository.save(entry);
        audit(null, "WATCHLIST_UPDATED", "Trader %s added/updated on watchlist. Risk score: %d".formatted(traderId, riskScore));
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Transactional
    public void audit(String alertId, String eventType, String description) {
        AuditEventEntity event = new AuditEventEntity();
        event.setAlertId(alertId);
        event.setEventType(eventType);
        event.setDescription(description);
        event.setActor("SYSTEM");
        event.setEventTime(Instant.now());
        auditEventRepository.save(event);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("JSON serialisation failed: {}", e.getMessage());
            return "{}";
        }
    }
}
