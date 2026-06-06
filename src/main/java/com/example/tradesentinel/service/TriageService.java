package com.example.tradesentinel.service;

import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.TriageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TriageService {
  private static final String DEFAULT_MODEL = "claude-3-5-sonnet-latest";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public TriageService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public TriageResult triage(Alert alert) {
    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      return offlineTriage(alert);
    }
    return claudeTriage(alert, apiKey);
  }

  public boolean claudeConfigured() {
    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    return apiKey != null && !apiKey.isBlank();
  }

  TriageResult offlineTriage(Alert alert) {
    int falsePositive = Math.max(3, Math.min(60, 100 - alert.score()));
    String verdict = alert.score() >= 75 ? "ESCALATE" : "REVIEW";
    String reason;
    List<String> riskFactors;
    List<String> falsePositiveFactors;
    List<String> recommendedActions;
    if (alert.pattern().startsWith("Layering")) {
      reason = "%s large orders were cancelled with %s%% cancellation and median cancel time %sms."
          .formatted(
              alert.metrics().get("largeCancelledOrders"),
              alert.metrics().get("cancelRatioPct"),
              alert.metrics().get("medianCancelTimeMs"));
      riskFactors = List.of(
          "High cancellation ratio versus sample baseline",
          "Large visible orders cancelled rapidly",
          "Opposite-side executions occurred during the cancellation window");
      falsePositiveFactors = List.of(
          "No external market news feed is available in this demo",
          "Replay dataset provides a compact baseline rather than a full 30-day trader profile");
      recommendedActions = List.of("Open L2 surveillance case", "Review order book context", "Apply temporary enhanced monitoring");
    } else if (alert.pattern().startsWith("Wash")) {
      reason = "%s rapid buy/sell cycles occurred in %s seconds with near-matched quantities."
          .formatted(alert.metrics().get("buySellCycles"), alert.metrics().get("windowSeconds"));
      riskFactors = List.of(
          "Repeated opposite-side executions by the same trader",
          "Near-matched quantities inside a short window",
          "Executed volume is concentrated in a single trader-symbol pair");
      falsePositiveFactors = List.of(
          "Legitimate hedging cannot be fully ruled out from order events alone",
          "No beneficial-owner relationship data is connected in this MVP");
      recommendedActions = List.of("Create wash-trade review case", "Check account ownership links", "Preserve execution evidence");
    } else {
      reason = "%s aggressive %s executions moved price by %s%% in %s seconds."
          .formatted(
              alert.metrics().get("aggressiveExecutions"),
              alert.metrics().get("dominantSide"),
              alert.metrics().get("priceMovePct"),
              alert.metrics().get("windowSeconds"));
      riskFactors = List.of(
          "Rapid same-side executions created short-window price impact",
          "Executed quantity is large enough to influence visible momentum",
          "Pattern can attract follow-on liquidity before the trader exits");
      falsePositiveFactors = List.of(
          "Could be legitimate urgent execution without client-intent data",
          "Market-wide price movement is not available in the demo dataset");
      recommendedActions = List.of("Escalate for momentum-ignition review", "Compare with market index movement", "Monitor trader for reversal trades");
    }
    String caseNote = "%s %s alert for trader %s in %s. %s Verdict: %s with %d%% confidence."
        .formatted(alert.severity(), alert.pattern(), alert.traderId(), alert.symbol(), reason, verdict, Math.min(96, Math.max(65, alert.score())));
    return new TriageResult(
        verdict,
        Math.min(96, Math.max(65, alert.score())),
        falsePositive,
        reason,
        riskFactors,
        falsePositiveFactors,
        recommendedActions,
        caseNote,
        "offline-rule-triage");
  }

  private TriageResult claudeTriage(Alert alert, String apiKey) {
    String model = System.getenv().getOrDefault("ANTHROPIC_MODEL", DEFAULT_MODEL);
    try {
      Map<String, Object> prompt = Map.of(
          "role", "compliance analyst",
          "task", "Triage this trade surveillance alert. Decide if it should be escalated or ignored.",
          "rubric", List.of(
              "Treat rules as candidate alerts, not proof of misconduct.",
              "Escalate only when evidence is coherent, severe, and hard to explain as benign activity.",
              "Explicitly separate suspicious risk factors from false-positive factors.",
              "Prefer concise compliance case-note language suitable for an analyst queue."),
          "required_json_schema", Map.of(
              "verdict", "ESCALATE | IGNORE | REVIEW",
              "confidence", "integer 0-100",
              "falsePositiveProbability", "integer 0-100",
              "reason", "short reason for a compliance case note",
              "riskFactors", "array of 2-5 strings",
              "falsePositiveFactors", "array of 1-4 strings",
              "recommendedActions", "array of 2-4 strings",
              "caseNote", "one paragraph analyst-ready note"),
          "alert", alert);
      Map<String, Object> body = Map.of(
          "model", model,
          "max_tokens", 500,
          "temperature", 0.1,
          "messages", List.of(Map.of(
              "role", "user",
              "content", "Return only valid JSON. Analyze the alert below as a financial markets surveillance analyst:\n"
                  + objectMapper.writeValueAsString(prompt))));

      HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
          .timeout(Duration.ofSeconds(20))
          .header("content-type", "application/json")
          .header("x-api-key", apiKey)
          .header("anthropic-version", "2023-06-01")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Claude returned HTTP " + response.statusCode());
      }
      JsonNode payload = objectMapper.readTree(response.body());
      String text = payload.path("content").path(0).path("text").asText();
      JsonNode parsed = objectMapper.readTree(text);
      return new TriageResult(
          parsed.path("verdict").asText("REVIEW").toUpperCase(),
          parsed.path("confidence").asInt(70),
          parsed.path("falsePositiveProbability").asInt(30),
          parsed.path("reason").asText("Claude returned a triage verdict."),
          readStringList(parsed.path("riskFactors")),
          readStringList(parsed.path("falsePositiveFactors")),
          readStringList(parsed.path("recommendedActions")),
          parsed.path("caseNote").asText(parsed.path("reason").asText("Claude returned a triage verdict.")),
          "claude:" + model);
    } catch (Exception exception) {
      TriageResult fallback = offlineTriage(alert);
      return new TriageResult(
          fallback.verdict(),
          fallback.confidence(),
          fallback.falsePositiveProbability(),
          "Claude triage unavailable; offline fallback used. " + fallback.reason(),
          fallback.riskFactors(),
          fallback.falsePositiveFactors(),
          fallback.recommendedActions(),
          fallback.caseNote(),
          "fallback-after-claude-error:" + exception.getClass().getSimpleName());
    }
  }

  private List<String> readStringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    java.util.ArrayList<String> values = new java.util.ArrayList<>();
    node.forEach(item -> values.add(item.asText()));
    return values;
  }
}
