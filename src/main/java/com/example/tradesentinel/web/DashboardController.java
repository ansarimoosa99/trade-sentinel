package com.example.tradesentinel.web;

import com.example.tradesentinel.entity.*;
import com.example.tradesentinel.repository.*;
import com.example.tradesentinel.service.ScenarioScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

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
