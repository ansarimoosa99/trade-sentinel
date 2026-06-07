package com.example.tradesentinel.web;

import com.example.tradesentinel.entity.*;
import com.example.tradesentinel.repository.*;
import com.example.tradesentinel.service.ScenarioScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final SurveillanceAlertRepository alertRepository;
    private final TriageResultRepository triageRepository;
    private final ComplianceCaseRepository caseRepository;
    private final WatchlistEntryRepository watchlistRepository;
    private final AuditEventRepository auditRepository;
    private final OrderEventRepository orderEventRepository;
    private final NotificationRepository notificationRepository;

    @Autowired(required = false)
    private ScenarioScheduler scenarioScheduler;

    // ── Pages ─────────────────────────────────────────────────────────────────

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("tab", "trades");
        addSummaryStats(model);
        return "dashboard";
    }

    @GetMapping("/alerts")
    public String alerts(Model model) {
        List<SurveillanceAlertEntity> alerts = alertRepository.findTop50ByOrderByCreatedAtDesc();
        Map<String, TriageResultEntity> triageByAlert = new LinkedHashMap<>();
        for (SurveillanceAlertEntity alert : alerts) {
            triageRepository.findByAlertId(alert.getAlertId()).ifPresent(t -> triageByAlert.put(alert.getAlertId(), t));
        }
        model.addAttribute("tab", "alerts");
        model.addAttribute("alerts", alerts);
        model.addAttribute("triageByAlert", triageByAlert);
        addSummaryStats(model);
        return "dashboard";
    }

    @GetMapping("/cases")
    public String cases(Model model) {
        model.addAttribute("tab", "cases");
        model.addAttribute("cases", caseRepository.findTop50ByOrderByCreatedAtDesc());
        addSummaryStats(model);
        return "dashboard";
    }

    @GetMapping("/watchlist")
    public String watchlist(Model model) {
        model.addAttribute("tab", "watchlist");
        model.addAttribute("watchlist", watchlistRepository.findAllByOrderByRiskScoreDesc());
        addSummaryStats(model);
        return "dashboard";
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("tab", "audit");
        model.addAttribute("auditEvents", auditRepository.findTop100ByOrderByEventTimeDesc());
        addSummaryStats(model);
        return "dashboard";
    }

    // ── Chart data API ────────────────────────────────────────────────────────

    @GetMapping("/api/charts/data")
    @ResponseBody
    public Map<String, Object> chartData() {
        Map<String, Object> data = new LinkedHashMap<>();

        // Alert distribution by severity
        Map<String, Long> alertBySeverity = new LinkedHashMap<>();
        for (Object[] row : alertRepository.countBySeverity()) {
            alertBySeverity.put((String) row[0], (Long) row[1]);
        }
        data.put("alertBySeverity", alertBySeverity);

        // Verdict distribution
        Map<String, Long> verdictDist = new LinkedHashMap<>();
        for (Object[] row : triageRepository.countByVerdict()) {
            verdictDist.put((String) row[0], (Long) row[1]);
        }
        data.put("verdictDistribution", verdictDist);

        // Case status distribution
        Map<String, Long> caseDist = new LinkedHashMap<>();
        for (Object[] row : caseRepository.countByStatus()) {
            caseDist.put((String) row[0], (Long) row[1]);
        }
        data.put("caseDistribution", caseDist);

        // Recent risk scores (last 20 alerts) for trend line
        List<Map<String, Object>> riskTrend = new ArrayList<>();
        List<SurveillanceAlertEntity> recent = alertRepository.findTop50ByOrderByCreatedAtDesc();
        for (SurveillanceAlertEntity alert : recent.subList(0, Math.min(20, recent.size()))) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("alertId", alert.getAlertId());
            point.put("riskScore", alert.getRiskScore());
            point.put("createdAt", alert.getCreatedAt());
            point.put("pattern", alert.getPattern());
            riskTrend.add(point);
        }
        Collections.reverse(riskTrend);
        data.put("riskTrend", riskTrend);

        return data;
    }

    // ── Alerts REST API (for live refresh) ────────────────────────────────────

    @GetMapping("/api/alerts/recent")
    @ResponseBody
    public List<Map<String, Object>> recentAlerts() {
        List<SurveillanceAlertEntity> alerts = alertRepository.findTop50ByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (SurveillanceAlertEntity alert : alerts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alertId",   alert.getAlertId());
            m.put("pattern",   alert.getPattern());
            m.put("symbol",    alert.getSymbol());
            m.put("traderId",  alert.getTraderId());
            m.put("severity",  alert.getSeverity());
            m.put("score",     alert.getScore());
            m.put("riskScore", alert.getRiskScore());
            m.put("createdAt", alert.getCreatedAt());
            triageRepository.findByAlertId(alert.getAlertId()).ifPresent(t -> {
                Map<String, Object> triage = new LinkedHashMap<>();
                triage.put("verdict",       t.getVerdict());
                triage.put("confidence",    t.getConfidence());
                triage.put("fpProbability", t.getFpProbability());
                triage.put("source",        t.getSource());
                triage.put("caseNote",      t.getCaseNote());
                m.put("triage", triage);
            });
            result.add(m);
        }
        return result;
    }

    // ── Cases REST API (for live refresh) ─────────────────────────────────────

    @GetMapping("/api/cases/recent")
    @ResponseBody
    public List<Map<String, Object>> recentCases() {
        return caseRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("caseId",     c.getCaseId());
                    m.put("alertId",    c.getAlertId());
                    m.put("status",     c.getStatus());
                    m.put("priority",   c.getPriority());
                    m.put("assignedTo", c.getAssignedTo());
                    m.put("createdAt",  c.getCreatedAt());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Audit REST API (server-side filter + pagination) ───────────────────────

    @GetMapping("/api/audit")
    @ResponseBody
    public Map<String, Object> auditApi(
            @RequestParam(defaultValue = "") String eventType,
            @RequestParam(defaultValue = "") String searchText,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<AuditEventEntity> result = auditRepository.findFiltered(eventType, searchText, pr);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", result.getContent().stream().map(this::auditToMap).toList());
        resp.put("totalElements", result.getTotalElements());
        resp.put("totalPages", result.getTotalPages());
        resp.put("page", page);
        resp.put("size", size);
        return resp;
    }

    // ── Events stream for toast notifications ──────────────────────────────────

    @GetMapping("/api/events/recent")
    @ResponseBody
    public List<Map<String, Object>> recentEvents(
            @RequestParam(defaultValue = "0") long since) {
        Instant sinceInstant = since > 0
                ? Instant.ofEpochMilli(since)
                : Instant.now().minusSeconds(5);
        return auditRepository.findTop100ByOrderByEventTimeDesc().stream()
                .filter(e -> e.getEventTime() != null && e.getEventTime().isAfter(sinceInstant))
                .map(this::auditToMap)
                .toList();
    }

    private Map<String, Object> auditToMap(AuditEventEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("alertId", a.getAlertId());
        m.put("eventType", a.getEventType());
        m.put("description", a.getDescription());
        m.put("actor", a.getActor());
        m.put("eventTime", a.getEventTime());
        return m;
    }

    // ── Notifications REST (for dashboard polling) ─────────────────────────────

    @GetMapping("/api/notifications/recent")
    @ResponseBody
    public List<Map<String, Object>> recentNotifications() {
        return notificationRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.getNotificationId());
                    m.put("alertId", n.getAlertId());
                    m.put("channel", n.getChannel());
                    m.put("status", n.getStatus());
                    m.put("createdAt", n.getCreatedAt());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Summary stats helper ───────────────────────────────────────────────────

    private void addSummaryStats(Model model) {
        model.addAttribute("totalAlerts", alertRepository.count());
        model.addAttribute("openCases", caseRepository.findByStatus("OPEN").size());
        model.addAttribute("watchlistSize", watchlistRepository.findByStatus("ACTIVE").size());
        model.addAttribute("totalEvents", orderEventRepository.count());
        model.addAttribute("totalNotifications", notificationRepository.count());
    }
}
