package com.example.tradesentinel.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Alert(
    @JsonProperty("alert_id") String alertId,
    String pattern,
    @JsonProperty("trader_id") String traderId,
    @JsonProperty("account_id") String accountId,
    String symbol,
    String severity,
    int score,
    Map<String, Object> metrics,
    List<OrderEvent> evidence,
    @JsonProperty("created_at") Instant createdAt) {

  public Alert withAlertId(String nextAlertId) {
    return new Alert(nextAlertId, pattern, traderId, accountId, symbol, severity, score, metrics, evidence, createdAt);
  }
}
