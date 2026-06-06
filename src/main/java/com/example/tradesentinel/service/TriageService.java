package com.example.tradesentinel.service;

import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.TriageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TriageService {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final boolean anthropicEnabled;
    private final String anthropicApiKey;
    private final String anthropicModel;
    private final boolean highSeverityOnly;
    private final RateLimiter rateLimiter;

    public TriageService(
            ObjectMapper objectMapper,
            @Value("${anthropic.enabled:true}") boolean anthropicEnabled,
            @Value("${anthropic.api-key:}") String anthropicApiKey,
            @Value("${anthropic.model:claude-sonnet-4-20250514}") String anthropicModel,
            @Value("${anthropic.high-severity-only:true}") boolean highSeverityOnly,
            @Value("${anthropic.rate-limit-per-second:5}") double rateLimitPerSecond) {
        this.objectMapper = objectMapper;
        this.anthropicEnabled = anthropicEnabled;
        this.anthropicApiKey = anthropicApiKey;
        this.anthropicModel = anthropicModel;
        this.highSeverityOnly = highSeverityOnly;
        this.rateLimiter = RateLimiter.create(rateLimitPerSecond > 0 ? rateLimitPerSecond : 5.0);
    }

    public TriageResult triage(Alert alert) {
        boolean claudeAvailable = anthropicEnabled && anthropicApiKey != null && !anthropicApiKey.isBlank();
        if (!claudeAvailable) return offlineTriage(alert);

        // Only send HIGH / CRITICAL to Claude when high-severity-only is configured
        if (highSeverityOnly && !isHighOrCritical(alert)) {
            return offlineTriage(alert);
        }

        // Acquire rate limiter slot (non-blocking: if cannot acquire, fall back to offline)
        if (!rateLimiter.tryAcquire()) {
            log.warn("Claude rate limit hit for alert {} — falling back to offline triage", alert.alertId());
            return offlineTriage(alert);
        }

        return claudeTriage(alert, anthropicApiKey);
    }

    public boolean claudeConfigured() {
        return anthropicEnabled && anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    private boolean isHighOrCritical(Alert alert) {
        String sev = alert.severity();
        return "HIGH".equalsIgnoreCase(sev) || "CRITICAL".equalsIgnoreCase(sev);
    }

    TriageResult offlineTriage(Alert alert) {
        int falsePositive = Math.max(3, Math.min(60, 100 - alert.score()));
        String verdict = alert.score() >= 75 ? "ESCALATE" : "REVIEW";
        String reason;
        List<String> riskFactors;
        List<String> falsePositiveFactors;
        List<String> recommendedActions;

        if (alert.pattern().startsWith("Layering")) {
            reason = "%s large orders cancelled with %s%% cancel ratio, median %sms."
                    .formatted(alert.metrics().get("largeCancelledOrders"),
                            alert.metrics().get("cancelRatioPct"),
                            alert.metrics().get("medianCancelTimeMs"));
            riskFactors = List.of("High cancel ratio vs baseline", "Large visible orders cancelled rapidly",
                    "Opposite-side executions during cancellation window");
            falsePositiveFactors = List.of("No live news feed available", "Compact replay baseline");
            recommendedActions = List.of("Open L2 surveillance case", "Review order book context",
                    "Apply enhanced monitoring");
        } else if (alert.pattern().startsWith("Wash")) {
            reason = "%s rapid buy/sell cycles in %s seconds with near-matched quantities."
                    .formatted(alert.metrics().get("buySellCycles"), alert.metrics().get("windowSeconds"));
            riskFactors = List.of("Repeated opposite-side executions", "Near-matched quantities in short window",
                    "Volume concentrated in one trader-symbol pair");
            falsePositiveFactors = List.of("Legitimate hedging cannot be ruled out",
                    "No beneficial-owner data in MVP");
            recommendedActions = List.of("Create wash-trade review case", "Check account ownership",
                    "Preserve execution evidence");
        } else if (alert.pattern().startsWith("Spoof")) {
            reason = "%s spoof orders placed and cancelled within %sms."
                    .formatted(alert.metrics().get("spoofOrderCount"), alert.metrics().get("avgCancelTimeMs"));
            riskFactors = List.of("Large orders cancelled before execution",
                    "Cancel time well below market norm", "Size ratio significantly above baseline");
            falsePositiveFactors = List.of("Could be genuine risk management", "Volatile market conditions");
            recommendedActions = List.of("Escalate for spoofing review", "Request trader explanation",
                    "Monitor for repeat pattern");
        } else if (alert.pattern().startsWith("Front")) {
            reason = "Trader executed %s ms before a large institutional order of %s units."
                    .formatted(alert.metrics().get("leadTimeMs"), alert.metrics().get("institutionalQty"));
            riskFactors = List.of("Execution immediately preceded large institutional order",
                    "Same side and symbol as institutional flow", "Sub-500ms lead time");
            falsePositiveFactors = List.of("Could be coincidental timing", "No confirmed information access");
            recommendedActions = List.of("Investigate information access", "Review communication records",
                    "Escalate to compliance");
        } else {
            reason = "%s aggressive %s executions moved price by %s%% in %s seconds."
                    .formatted(alert.metrics().get("aggressiveExecutions"), alert.metrics().get("dominantSide"),
                            alert.metrics().get("priceMovePct"), alert.metrics().get("windowSeconds"));
            riskFactors = List.of("Rapid same-side executions created price impact",
                    "Large enough quantity to influence momentum",
                    "Pattern can attract follow-on liquidity");
            falsePositiveFactors = List.of("Could be legitimate urgent execution",
                    "Market-wide movement not available in dataset");
            recommendedActions = List.of("Escalate for momentum-ignition review",
                    "Compare with market index", "Monitor for reversal trades");
        }

        String caseNote = "%s %s alert for trader %s in %s. %s Verdict: %s (%d%% confidence)."
                .formatted(alert.severity(), alert.pattern(), alert.traderId(),
                        alert.symbol(), reason, verdict, Math.min(96, Math.max(65, alert.score())));

        return new TriageResult(
                verdict,
                Math.min(96, Math.max(65, alert.score())),
                falsePositive,
                reason, riskFactors, falsePositiveFactors, recommendedActions,
                caseNote, "offline-rule-triage");
    }

    private TriageResult claudeTriage(Alert alert, String apiKey) {
        String model = anthropicModel != null && !anthropicModel.isBlank() ? anthropicModel : DEFAULT_MODEL;
        try {
            Map<String, Object> prompt = Map.of(
                    "role", "compliance analyst",
                    "task", "Triage this trade surveillance alert. Decide if it should be escalated or ignored.",
                    "rubric", List.of(
                            "Treat rule triggers as candidate alerts, not proof of misconduct.",
                            "Escalate only when evidence is coherent, severe, and hard to explain as benign.",
                            "Separate suspicious risk factors from false-positive factors clearly.",
                            "Verdict must be one of: ESCALATE, REVIEW, IGNORE"),
                    "required_json_schema", Map.of(
                            "verdict", "ESCALATE | REVIEW | IGNORE",
                            "confidence", "integer 0-100",
                            "falsePositiveProbability", "integer 0-100",
                            "reason", "short reason for compliance case note",
                            "riskFactors", "array of 2-5 strings",
                            "falsePositiveFactors", "array of 1-4 strings",
                            "recommendedActions", "array of 2-4 strings",
                            "caseNote", "one paragraph analyst-ready note"),
                    "alert", alert);

            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 600,
                    "temperature", 0.1,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", "Return only valid JSON. Analyze as a financial markets surveillance analyst:\n"
                                    + objectMapper.writeValueAsString(prompt))));

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(25))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Claude HTTP " + response.statusCode());
            }
            JsonNode payload = objectMapper.readTree(response.body());
            String text = payload.path("content").path(0).path("text").asText();
            JsonNode parsed = objectMapper.readTree(stripCodeFences(text));

            return new TriageResult(
                    parsed.path("verdict").asText("REVIEW").toUpperCase(),
                    parsed.path("confidence").asInt(70),
                    parsed.path("falsePositiveProbability").asInt(30),
                    parsed.path("reason").asText("Claude returned a triage verdict."),
                    readStringList(parsed.path("riskFactors")),
                    readStringList(parsed.path("falsePositiveFactors")),
                    readStringList(parsed.path("recommendedActions")),
                    parsed.path("caseNote").asText(parsed.path("reason").asText("Claude triage verdict.")),
                    "claude:" + model);

        } catch (Exception ex) {
            log.warn("Claude triage failed for alert {}: {} — using offline fallback", alert.alertId(), ex.getMessage());
            TriageResult fallback = offlineTriage(alert);
            return new TriageResult(
                    fallback.verdict(), fallback.confidence(), fallback.falsePositiveProbability(),
                    "Claude unavailable; offline fallback. " + fallback.reason(),
                    fallback.riskFactors(), fallback.falsePositiveFactors(), fallback.recommendedActions(),
                    fallback.caseNote(),
                    "fallback-after-claude-error:" + ex.getClass().getSimpleName());
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    /** Strips markdown code fences Claude sometimes wraps JSON in (```json ... ```). */
    private String stripCodeFences(String text) {
        if (text == null) return "";
        String t = text.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            t = t.replaceFirst("\\s*```$", "");
        }
        return t.strip();
    }
}
