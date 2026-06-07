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

        // Extreme patterns (score >= 99) always create a watchlist entry for demo fidelity
        if (alert.score() >= 99) decision = EscalationDecision.CASE_NOTIFY_WATCHLIST;
        // Very high patterns (score 98) always send notifications
        else if (alert.score() >= 98 && decision.ordinal() < EscalationDecision.CASE_AND_NOTIFY.ordinal()) {
            decision = EscalationDecision.CASE_AND_NOTIFY;
        }

        persistenceService.updateAlertRiskScore(alert.alertId(), riskScore);
        persistenceService.audit(alert.alertId(), "RISK_SCORED",
                "Risk score %d → decision: %s".formatted(riskScore, decision));

        switch (decision) {
            case IGNORE:
                persistenceService.audit(alert.alertId(), "ALERT_IGNORED",
                        "Risk score %d — false positive, no action taken".formatted(riskScore));
                break;

            case REVIEW:
                // Medium-risk alerts are logged for analyst review — no formal compliance case
                persistenceService.audit(alert.alertId(), "ALERT_REVIEWED",
                        "Risk score %d — flagged for analyst review, no escalation".formatted(riskScore));
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
                        "Alert %s — risk %d — %s".formatted(alert.alertId(), riskScore, alert.pattern()));
                persistenceService.audit(alert.alertId(), "WATCHLIST_AUTO_ADD",
                        "Trader %s watchlisted (risk score %d)".formatted(alert.traderId(), riskScore));
                break;
        }

        log.info("Alert {} | risk={} | score={} | decision={} | pattern={}",
                alert.alertId(), riskScore, alert.score(), decision, alert.pattern());
        return decision;
    }
}
