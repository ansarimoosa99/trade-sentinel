package com.example.tradesentinel.service;

import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.TriageResult;
import com.example.tradesentinel.repository.WatchlistEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Risk Score = 30% Detection Confidence + 25% Baseline Deviation
 *            + 25% Claude Confidence + 20% Historical Trader Risk
 * Range: 0-100
 */
@Service
@RequiredArgsConstructor
public class RiskScoreEngine {

    private final WatchlistEntryRepository watchlistRepository;

    public int compute(Alert alert, TriageResult triage) {
        double detectionScore = alert.score() * 0.30;

        // Baseline deviation: compare alert score vs neutral 50 mid-point
        double baselineDeviation = Math.min(100, Math.abs(alert.score() - 50) * 2) * 0.25;

        double claudeScore = triage.confidence() * 0.25;

        // Historical trader risk: elevated if trader is already on watchlist
        int historicalRisk = watchlistRepository.findByTraderId(alert.traderId())
                .map(e -> Math.min(100, e.getRiskScore() + 20))
                .orElse(20); // default 20 for first-time traders
        double historicalScore = historicalRisk * 0.20;

        int total = (int) Math.min(100, detectionScore + baselineDeviation + claudeScore + historicalScore);
        return Math.max(0, total);
    }

    /**
     * Escalation matrix — broadened REVIEW band so medium-risk alerts don't
     * automatically become compliance cases. Score overrides in EscalationService
     * handle extreme patterns (score 98+) regardless of numeric risk score.
     * 0-42   → IGNORE (false positive)
     * 43-78  → REVIEW (analyst review, no formal case)
     * 79-87  → CASE (P1 compliance case)
     * 88-95  → CASE_AND_NOTIFY (P1 + email + Slack)
     * 96-100 → CASE_NOTIFY_WATCHLIST (P0 + notifications + watchlist)
     */
    public EscalationDecision decide(int riskScore) {
        if (riskScore <= 42) return EscalationDecision.IGNORE;
        if (riskScore <= 69) return EscalationDecision.REVIEW;   // Below all attack pattern floors
        if (riskScore <= 87) return EscalationDecision.CASE;     // All 5 attack patterns land here
        if (riskScore <= 95) return EscalationDecision.CASE_AND_NOTIFY;
        return EscalationDecision.CASE_NOTIFY_WATCHLIST;
    }

    public enum EscalationDecision {
        IGNORE, REVIEW, CASE, CASE_AND_NOTIFY, CASE_NOTIFY_WATCHLIST
    }
}
