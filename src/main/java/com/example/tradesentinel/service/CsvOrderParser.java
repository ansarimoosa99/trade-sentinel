package com.example.tradesentinel.service;

import com.example.tradesentinel.model.OrderEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CsvOrderParser {
  private static final List<String> REQUIRED_COLUMNS = List.of(
      "order_id", "trader_id", "account_id", "symbol", "exchange",
      "side", "quantity", "price", "event_type", "event_time");

  public List<OrderEvent> parse(String csvText) {
    List<List<String>> rows = readRows(csvText == null ? "" : csvText);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("CSV is empty");
    }

    List<String> headers = rows.getFirst().stream().map(String::trim).toList();
    List<String> missing = REQUIRED_COLUMNS.stream().filter(column -> !headers.contains(column)).toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Missing CSV columns: " + String.join(", ", missing));
    }

    List<OrderEvent> events = new ArrayList<>();
    for (int i = 1; i < rows.size(); i++) {
      List<String> row = rows.get(i);
      if (row.stream().allMatch(String::isBlank)) {
        continue;
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (int column = 0; column < headers.size(); column++) {
        values.put(headers.get(column), column < row.size() ? row.get(column).trim() : "");
      }
      events.add(new OrderEvent(
          values.get("order_id"),
          values.get("trader_id"),
          values.get("account_id"),
          values.get("symbol").toUpperCase(),
          values.get("exchange").toUpperCase(),
          values.get("side").toUpperCase(),
          (int) Double.parseDouble(values.get("quantity")),
          Double.parseDouble(values.get("price")),
          values.get("event_type").toUpperCase(),
          parseInstant(values.get("event_time"))));
    }

    events.sort(Comparator.comparing(OrderEvent::eventTime));
    return events;
  }

  private Instant parseInstant(String raw) {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException ignored) {
      return OffsetDateTime.parse(raw).withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }
  }

  private List<List<String>> readRows(String csvText) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;

    for (int i = 0; i < csvText.length(); i++) {
      char current = csvText.charAt(i);
      if (current == '"') {
        if (inQuotes && i + 1 < csvText.length() && csvText.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (current == ',' && !inQuotes) {
        row.add(field.toString());
        field.setLength(0);
      } else if ((current == '\n' || current == '\r') && !inQuotes) {
        if (current == '\r' && i + 1 < csvText.length() && csvText.charAt(i + 1) == '\n') {
          i++;
        }
        row.add(field.toString());
        rows.add(row);
        row = new ArrayList<>();
        field.setLength(0);
      } else {
        field.append(current);
      }
    }

    if (!field.isEmpty() || !row.isEmpty()) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }
}
