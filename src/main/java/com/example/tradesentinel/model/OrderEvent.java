package com.example.tradesentinel.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record OrderEvent(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("trader_id") String traderId,
    @JsonProperty("account_id") String accountId,
    String symbol,
    String exchange,
    String side,
    int quantity,
    double price,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("event_time") Instant eventTime) {}
