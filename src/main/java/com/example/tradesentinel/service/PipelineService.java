package com.example.tradesentinel.service;

import com.example.tradesentinel.model.ActionResult;
import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.EscalationResult;
import com.example.tradesentinel.model.OrderEvent;
import com.example.tradesentinel.model.PipelineResult;
import com.example.tradesentinel.model.PipelineSummary;
import com.example.tradesentinel.model.TriageResult;
import com.example.tradesentinel.model.TriagedAlert;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PipelineService {
  private final CsvOrderParser parser;
  private final DetectionService detectionService;
  private final TriageService triageService;

  public PipelineService(CsvOrderParser parser, DetectionService detectionService, TriageService triageService) {
    this.parser = parser;
    this.detectionService = detectionService;
    this.triageService = triageService;
  }

  public PipelineResult run(String csvText, int replayDelayMs) {
    List<OrderEvent> events = parser.parse(csvText);
    if (replayDelayMs > 0) {
      events.forEach(ignored -> sleep(replayDelayMs));
    }

    List<Alert> alerts = detectionService.detectAlerts(events);
    List<TriagedAlert> triagedAlerts = new ArrayList<>();
    List<EscalationResult> escalations = new ArrayList<>();
    for (Alert alert : alerts) {
      TriageResult triage = triageService.triage(alert);
      triagedAlerts.add(new TriagedAlert(alert, triage));
      escalations.add(escalate(alert, triage));
    }

    long escalated = triagedAlerts.stream().filter(item -> "ESCALATE".equals(item.triage().verdict())).count();
    long review = triagedAlerts.stream().filter(item -> "REVIEW".equals(item.triage().verdict())).count();
    long ignored = triagedAlerts.stream().filter(item -> "IGNORE".equals(item.triage().verdict())).count();
    long highSeverity = alerts.stream().filter(item -> "HIGH".equals(item.severity())).count();
    PipelineSummary summary = new PipelineSummary(
        events.size(),
        alerts.size(),
        (int) highSeverity,
        (int) escalated,
        (int) review,
        (int) ignored,
        triageService.claudeConfigured() ? "claude" : "offline");
    return new PipelineResult(summary, events, triagedAlerts, escalations);
  }

  private EscalationResult escalate(Alert alert, TriageResult triage) {
    List<ActionResult> actions;
    Instant createdAt = Instant.now();
    if ("ESCALATE".equals(triage.verdict())) {
      actions = List.of(
          action(alert, 1, "CASE_CREATED", "CASE-" + alert.alertId(), "OPEN", "Surveillance Desk L2", "P1", "2 hours", createdAt),
          action(alert, 2, "NOTIFICATION", "compliance-ops", "SENT", "Compliance Ops", "P1", "15 minutes", createdAt),
          action(alert, 3, "WATCHLIST_UPDATE", alert.traderId(), "ENABLED", "Market Abuse Monitoring", "P2", "72 hours", createdAt));
    } else if ("REVIEW".equals(triage.verdict())) {
      actions = List.of(
          action(alert, 1, "CASE_CREATED", "REVIEW-" + alert.alertId(), "PENDING", "Surveillance Desk L1", "P2", "1 business day", createdAt),
          action(alert, 2, "ANALYST_TASK", "false-positive review", "QUEUED", "Surveillance Desk L1", "P3", "1 business day", createdAt));
    } else {
      actions = List.of(
          action(alert, 1, "SUPPRESSED", alert.alertId(), "IGNORED", "Surveillance Rules Engine", "P4", "audit retained", createdAt),
          action(alert, 2, "AUDIT_LOG", "triage-decision", "RECORDED", "Compliance Audit", "P4", "immutable", createdAt));
    }
    return new EscalationResult(alert.alertId(), actions);
  }

  private ActionResult action(
      Alert alert,
      int sequence,
      String type,
      String target,
      String status,
      String owner,
      String priority,
      String sla,
      Instant createdAt) {
    return new ActionResult(
        "%s-ACT-%02d".formatted(alert.alertId(), sequence),
        type,
        target,
        status,
        owner,
        priority,
        sla,
        createdAt);
  }

  private void sleep(int replayDelayMs) {
    try {
      Thread.sleep(replayDelayMs);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Replay interrupted", exception);
    }
  }
}
