package com.example.tradesentinel.service;

import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.TriageResult;
import com.example.tradesentinel.service.RiskScoreEngine.EscalationDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationService {

    private final RiskScoreEngine riskScoreEngine;
    private final PersistenceService persistenceService;

    @Autowired(required = false)
    private NotificationService notificationService;

    public EscalationDecision process(Alert alert, TriageResult triage) {
        int riskScore = riskScoreEngine.compute(alert, triage);
        EscalationDecision decision = riskScoreEngine.decide(riskScore);

        // Always persist the computed risk score
        persistenceService.updateAlertRiskScore(alert.alertId(), riskScore);
        persistenceService.audit(alert.alertId(), "RISK_SCORED",
                "Risk score %d → decision: %s".formatted(riskScore, decision));

        switch (decision) {
            case IGNORE:
                persistenceService.audit(alert.alertId(), "ALERT_IGNORED",
                        "Risk score %d below threshold — no action".formatted(riskScore));
                break;

            case REVIEW:
                persistenceService.createCase(alert.alertId(), "P2", "Surveillance Desk L1");
                break;

            case CASE:
                persistenceService.createCase(alert.alertId(), "P1", "Surveillance Desk L2");
                break;

            case CASE_AND_NOTIFY:
                persistenceService.createCase(alert.alertId(), "P1", "Surveillance Desk L2");
                if (notificationService != null) notificationService.notify(alert, triage, riskScore);
                persistenceService.audit(alert.alertId(), "NOTIFICATION_SENT",
                        "Risk score %d → email + Slack notification dispatched".formatted(riskScore));
                break;

            case CASE_NOTIFY_WATCHLIST:
                persistenceService.createCase(alert.alertId(), "P0", "Compliance Head");
                if (notificationService != null) notificationService.notify(alert, triage, riskScore);
                persistenceService.addToWatchlist(alert.traderId(), riskScore,
                        "Auto-added: risk score %d for %s".formatted(riskScore, alert.pattern()));
                persistenceService.audit(alert.alertId(), "WATCHLIST_AUTO_ADD",
                        "Trader %s watchlisted (risk score %d)".formatted(alert.traderId(), riskScore));
                break;
        }

        log.info("Alert {} | risk={} | decision={} | pattern={}", alert.alertId(), riskScore, decision, alert.pattern());
        return decision;
    }
}
