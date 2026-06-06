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
                .orElse(30); // default 30 for unknown traders
        double historicalScore = historicalRisk * 0.20;

        int total = (int) Math.min(100, detectionScore + baselineDeviation + claudeScore + historicalScore);
        return Math.max(0, total);
    }

    /**
     * Escalation matrix per imlpl-2.md:
     * 0-40   → FALSE_POSITIVE / ignore
     * 41-70  → REVIEW
     * 71-85  → REAL_ALERT → case
     * 86-95  → REAL_ALERT → case + notification
     * 96-100 → REAL_ALERT → case + notification + watchlist
     */
    public EscalationDecision decide(int riskScore) {
        if (riskScore <= 40) return EscalationDecision.IGNORE;
        if (riskScore <= 70) return EscalationDecision.REVIEW;
        if (riskScore <= 85) return EscalationDecision.CASE;
        if (riskScore <= 95) return EscalationDecision.CASE_AND_NOTIFY;
        return EscalationDecision.CASE_NOTIFY_WATCHLIST;
    }

    public enum EscalationDecision {
        IGNORE, REVIEW, CASE, CASE_AND_NOTIFY, CASE_NOTIFY_WATCHLIST
    }
}
