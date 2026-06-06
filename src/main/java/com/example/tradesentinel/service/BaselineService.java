package com.example.tradesentinel.service;

import com.example.tradesentinel.model.OrderEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BaselineService {
  public SurveillanceBaseline analyze(List<OrderEvent> events) {
    long newOrders = events.stream().filter(event -> "NEW".equals(event.eventType())).count();
    long cancels = events.stream().filter(event -> "CANCEL".equals(event.eventType())).count();
    double sampleCancelRatio = newOrders == 0 ? 0 : (double) cancels / newOrders;

    Map<String, TraderSymbolStats> stats = new HashMap<>();
    for (OrderEvent event : events) {
      stats.computeIfAbsent(key(event.traderId(), event.symbol()), ignored -> new TraderSymbolStats()).accept(event);
    }
    return new SurveillanceBaseline(sampleCancelRatio, stats);
  }

  private String key(String traderId, String symbol) {
    return traderId + "|" + symbol;
  }

  public record SurveillanceBaseline(double sampleCancelRatio, Map<String, TraderSymbolStats> stats) {
    public TraderSymbolStats statsFor(String traderId, String symbol) {
      return stats.getOrDefault(traderId + "|" + symbol, new TraderSymbolStats());
    }
  }

  public static class TraderSymbolStats {
    private int newOrders;
    private int cancels;
    private int executions;
    private long executedQuantity;
    private final List<OrderEvent> executionEvents = new ArrayList<>();

    void accept(OrderEvent event) {
      if ("NEW".equals(event.eventType())) {
        newOrders++;
      } else if ("CANCEL".equals(event.eventType())) {
        cancels++;
      } else if ("EXECUTE".equals(event.eventType())) {
        executions++;
        executedQuantity += event.quantity();
        executionEvents.add(event);
      }
    }

    public int newOrders() {
      return newOrders;
    }

    public int cancels() {
      return cancels;
    }

    public int executions() {
      return executions;
    }

    public long executedQuantity() {
      return executedQuantity;
    }

    public double cancelRatio() {
      return newOrders == 0 ? 0 : (double) cancels / newOrders;
    }

    public double averageExecutionQuantity() {
      return executions == 0 ? 0 : (double) executedQuantity / executions;
    }

    public double maxPriceMovePct() {
      if (executionEvents.size() < 2) {
        return 0;
      }
      double min = executionEvents.stream().mapToDouble(OrderEvent::price).min().orElse(0);
      double max = executionEvents.stream().mapToDouble(OrderEvent::price).max().orElse(0);
      return min == 0 ? 0 : ((max - min) / min) * 100;
    }

    public long executionWindowSeconds() {
      if (executionEvents.size() < 2) {
        return 0;
      }
      return Duration.between(executionEvents.getFirst().eventTime(), executionEvents.getLast().eventTime()).toSeconds();
    }
  }
}
