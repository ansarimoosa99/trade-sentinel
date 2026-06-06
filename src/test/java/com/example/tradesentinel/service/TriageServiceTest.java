package com.example.tradesentinel.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.TriageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TriageServiceTest {
  private final TriageService triageService = new TriageService(new ObjectMapper(), false, null, null, true, 5.0);

  @Test
  void offlineTriageReturnsStructuredAnalystOutput() {
    Alert alert = new Alert(
        "A-0001",
        "Momentum Ignition / Price Spike Manipulation",
        "T-7788",
        "A-909",
        "ICICIBANK",
        "HIGH",
        91,
        Map.of("aggressiveExecutions", 6, "dominantSide", "BUY", "priceMovePct", 2.28, "windowSeconds", 68),
        List.of(),
        Instant.now());

    TriageResult result = triageService.offlineTriage(alert);

    assertThat(result.verdict()).isEqualTo("ESCALATE");
    assertThat(result.confidence()).isGreaterThanOrEqualTo(90);
    assertThat(result.falsePositiveProbability()).isLessThan(15);
    assertThat(result.riskFactors()).isNotEmpty();
    assertThat(result.falsePositiveFactors()).isNotEmpty();
    assertThat(result.recommendedActions()).isNotEmpty();
    assertThat(result.caseNote()).contains("Momentum Ignition");
  }
}
