package com.example.tradesentinel.service;

import com.example.tradesentinel.model.OrderEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses the surveillance-style JSON format (trade_data.json) into OrderEvent objects.
 * Each JSON record can produce up to 3 events: NEW (always), CANCEL (if cancelled_at set),
 * EXECUTE (if filled_at set).
 */
@Component
public class JsonOrderParser {

  private final ObjectMapper objectMapper;

  public JsonOrderParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<OrderEvent> parse(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("JSON body is empty");
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode ordersNode = root.has("orders") ? root.get("orders") : root;
      if (!ordersNode.isArray()) {
        throw new IllegalArgumentException("Expected JSON object with 'orders' array or a top-level array");
      }

      List<OrderEvent> events = new ArrayList<>();
      for (JsonNode order : ordersNode) {
        String orderId   = text(order, "order_id");
        String traderId  = text(order, "trader_id");
        String accountId = traderId + "-ACC";
        String symbol    = text(order, "instrument", "symbol").toUpperCase();
        String exchange  = text(order, "exchange", "NSE");
        String side      = text(order, "side").toUpperCase();
        int    qty       = order.path("qty").asInt(order.path("quantity").asInt(0));
        double price     = order.path("price").asDouble(0);

        String placedAt     = textOrNull(order, "placed_at");
        String cancelledAt  = textOrNull(order, "cancelled_at");
        String filledAt     = textOrNull(order, "filled_at");

        if (placedAt != null) {
          events.add(new OrderEvent(orderId, traderId, accountId, symbol, exchange, side,
              qty, price, "NEW", Instant.parse(placedAt)));
        }
        if (cancelledAt != null && !cancelledAt.isBlank()) {
          events.add(new OrderEvent(orderId, traderId, accountId, symbol, exchange, side,
              qty, price, "CANCEL", Instant.parse(cancelledAt)));
        }
        if (filledAt != null && !filledAt.isBlank()) {
          events.add(new OrderEvent(orderId, traderId, accountId, symbol, exchange, side,
              qty, price, "EXECUTE", Instant.parse(filledAt)));
        }
      }

      if (events.isEmpty()) {
        throw new IllegalArgumentException("No valid order events found in JSON");
      }
      events.sort(Comparator.comparing(OrderEvent::eventTime));
      return events;
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid JSON format: " + ex.getMessage(), ex);
    }
  }

  private String text(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode child = node.path(key);
      if (!child.isMissingNode() && !child.isNull() && !child.asText().isBlank()) {
        return child.asText().trim();
      }
    }
    return "";
  }

  private String textOrNull(JsonNode node, String key) {
    JsonNode child = node.path(key);
    if (child.isMissingNode() || child.isNull()) {
      return null;
    }
    String value = child.asText("").trim();
    return value.isEmpty() ? null : value;
  }
}
