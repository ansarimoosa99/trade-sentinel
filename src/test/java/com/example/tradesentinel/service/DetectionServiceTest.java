package com.example.tradesentinel.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.OrderEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

class DetectionServiceTest {
  private final CsvOrderParser parser = new CsvOrderParser();
  private final DetectionService detectionService = new DetectionService(new BaselineService());

  @Test
  void detectsLayeringWashTradingAndMomentumIgnitionFromSample() throws Exception {
    List<OrderEvent> events = parser.parse(sampleCsv());

    List<Alert> alerts = detectionService.detectAlerts(events);

    assertThat(alerts).hasSize(3);
    assertThat(alerts).extracting(Alert::pattern)
        .containsExactlyInAnyOrder(
            "Layering / Spoofing",
            "Wash Trading",
            "Momentum Ignition / Price Spike Manipulation");
    assertThat(alerts.stream()
        .filter(alert -> alert.pattern().startsWith("Layering"))
        .findFirst()
        .orElseThrow()
        .severity()).isEqualTo("HIGH");
  }

  private String sampleCsv() throws Exception {
    return StreamUtils.copyToString(new ClassPathResource("sample_orders.csv").getInputStream(), StandardCharsets.UTF_8);
  }
}
