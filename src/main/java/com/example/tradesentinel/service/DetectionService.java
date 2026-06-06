package com.example.tradesentinel.service;

import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.OrderEvent;
import com.example.tradesentinel.service.BaselineService.SurveillanceBaseline;
import com.example.tradesentinel.service.BaselineService.TraderSymbolStats;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class DetectionService {
  private final BaselineService baselineService;

  public DetectionService(BaselineService baselineService) {
    this.baselineService = baselineService;
  }

  public List<Alert> detectAlerts(List<OrderEvent> events) {
    SurveillanceBaseline baseline = baselineService.analyze(events);
    List<Alert> rawAlerts = Stream.of(
            detectLayering(events, baseline),
            detectWashTrading(events, baseline),
            detectMomentumIgnition(events, baseline))
        .flatMap(List::stream)
        .toList();
    List<Alert> alerts = new ArrayList<>();
    for (int i = 0; i < rawAlerts.size(); i++) {
      alerts.add(rawAlerts.get(i).withAlertId("A-%04d".formatted(i + 1)));
    }
    return alerts;
  }

  private List<Alert> detectLayering(List<OrderEvent> events, SurveillanceBaseline baseline) {
    Map<String, OrderEvent> newByOrder = new HashMap<>();
    List<OrderEvent> cancels = new ArrayList<>();
    List<OrderEvent> executions = new ArrayList<>();
    for (OrderEvent event : events) {
      if ("NEW".equals(event.eventType())) {
        newByOrder.put(event.orderId(), event);
      } else if ("CANCEL".equals(event.eventType())) {
        cancels.add(event);
      } else if ("EXECUTE".equals(event.eventType())) {
        executions.add(event);
      }
    }

    Map<String, List<CancelledOrder>> grouped = new HashMap<>();
    for (OrderEvent cancel : cancels) {
      OrderEvent newEvent = newByOrder.get(cancel.orderId());
      if (newEvent == null) {
        continue;
      }
      double ageMs = Duration.between(newEvent.eventTime(), cancel.eventTime()).toNanos() / 1_000_000.0;
      if (ageMs >= 0) {
        grouped.computeIfAbsent(cancel.traderId() + "|" + cancel.symbol(), ignored -> new ArrayList<>())
            .add(new CancelledOrder(newEvent, cancel, ageMs));
      }
    }

    List<Alert> alerts = new ArrayList<>();
    for (Map.Entry<String, List<CancelledOrder>> entry : grouped.entrySet()) {
      List<CancelledOrder> cancelledOrders = entry.getValue();
      OrderEvent first = cancelledOrders.getFirst().newEvent();
      String traderId = first.traderId();
      String symbol = first.symbol();
      List<OrderEvent> newOrders = events.stream()
          .filter(event -> traderId.equals(event.traderId()) && symbol.equals(event.symbol()) && "NEW".equals(event.eventType()))
          .toList();
      List<CancelledOrder> largeOrders = cancelledOrders.stream()
          .filter(item -> item.newEvent().quantity() >= 25_000)
          .toList();
      if (largeOrders.size() < 6) {
        continue;
      }

      double cancelRatio = (double) cancelledOrders.size() / Math.max(newOrders.size(), 1);
      double medianCancelMs = median(cancelledOrders.stream().map(CancelledOrder::ageMs).sorted().toList());
      String dominantSide = countSide(cancelledOrders, "BUY") >= countSide(cancelledOrders, "SELL") ? "BUY" : "SELL";
      String oppositeSide = "BUY".equals(dominantSide) ? "SELL" : "BUY";
      List<OrderEvent> oppositeExecs = executions.stream()
          .filter(event -> traderId.equals(event.traderId()) && symbol.equals(event.symbol()) && oppositeSide.equals(event.side()))
          .toList();
      if (cancelRatio < 0.7 || medianCancelMs > 2000) {
        continue;
      }

      int score = Math.min(99, (int) (55 + cancelRatio * 25 + largeOrders.size() * 2 + Math.min(oppositeExecs.size(), 5) * 3));
      TraderSymbolStats stats = baseline.statsFor(traderId, symbol);
      List<OrderEvent> evidence = Stream.concat(
          largeOrders.stream().limit(8).map(CancelledOrder::newEvent),
          oppositeExecs.stream().limit(3)).toList();
      alerts.add(new Alert(
          "AL-%04d".formatted(alerts.size() + 1),
          "Layering / Spoofing",
          traderId,
          first.accountId(),
          symbol,
          score >= 85 ? "HIGH" : "MEDIUM",
          score,
          Map.of(
              "cancelRatioPct", round(cancelRatio * 100, 1),
              "sampleCancelRatioPct", round(baseline.sampleCancelRatio() * 100, 1),
              "traderCancelRatioPct", round(stats.cancelRatio() * 100, 1),
              "cancelRatioLift", round(cancelRatio / Math.max(baseline.sampleCancelRatio(), 0.01), 1),
              "medianCancelTimeMs", Math.round(medianCancelMs),
              "largeCancelledOrders", largeOrders.size(),
              "oppositeSideExecutions", oppositeExecs.size(),
              "dominantCancelledSide", dominantSide),
          evidence,
          Instant.now()));
    }
    return alerts;
  }

  private List<Alert> detectWashTrading(List<OrderEvent> events, SurveillanceBaseline baseline) {
    Map<String, List<OrderEvent>> groups = new HashMap<>();
    events.stream()
        .filter(event -> "EXECUTE".equals(event.eventType()))
        .forEach(event -> groups.computeIfAbsent(event.traderId() + "|" + event.symbol(), ignored -> new ArrayList<>()).add(event));

    List<Alert> alerts = new ArrayList<>();
    for (List<OrderEvent> group : groups.values()) {
      group.sort(Comparator.comparing(OrderEvent::eventTime));
      List<WashCycle> cycles = new ArrayList<>();
      for (int i = 0; i < group.size() - 1; i++) {
        OrderEvent left = group.get(i);
        OrderEvent right = group.get(i + 1);
        long seconds = Duration.between(left.eventTime(), right.eventTime()).toSeconds();
        double sizeDelta = Math.abs(left.quantity() - right.quantity()) / (double) Math.max(left.quantity(), right.quantity());
        if (!left.side().equals(right.side()) && seconds <= 60 && sizeDelta <= 0.15) {
          cycles.add(new WashCycle(left, right, seconds));
        }
      }
      if (cycles.size() < 3) {
        continue;
      }

      OrderEvent first = cycles.getFirst().left();
      OrderEvent last = cycles.getLast().right();
      long windowSeconds = Math.max(1, Duration.between(first.eventTime(), last.eventTime()).toSeconds());
      int totalQty = cycles.stream().mapToInt(pair -> pair.left().quantity() + pair.right().quantity()).sum();
      int score = Math.min(98, 60 + cycles.size() * 8 + Math.min(totalQty / 5000, 10));
      TraderSymbolStats stats = baseline.statsFor(first.traderId(), first.symbol());
      List<OrderEvent> evidence = cycles.stream().flatMap(pair -> Stream.of(pair.left(), pair.right())).limit(10).toList();
      alerts.add(new Alert(
          "AW-%04d".formatted(alerts.size() + 1),
          "Wash Trading",
          first.traderId(),
          first.accountId(),
          first.symbol(),
          score >= 85 ? "HIGH" : "MEDIUM",
          score,
          Map.of(
              "buySellCycles", cycles.size(),
              "windowSeconds", windowSeconds,
              "totalExecutedQuantity", totalQty,
              "avgExecutionQuantity", Math.round(stats.averageExecutionQuantity()),
              "traderExecutedQuantity", stats.executedQuantity(),
              "avgGapSeconds", round(cycles.stream().mapToLong(WashCycle::seconds).average().orElse(0), 1)),
          evidence,
          Instant.now()));
    }
    return alerts;
  }

  private List<Alert> detectMomentumIgnition(List<OrderEvent> events, SurveillanceBaseline baseline) {
    Map<String, List<OrderEvent>> groups = new HashMap<>();
    events.stream()
        .filter(event -> "EXECUTE".equals(event.eventType()))
        .forEach(event -> groups.computeIfAbsent(event.traderId() + "|" + event.symbol(), ignored -> new ArrayList<>()).add(event));

    List<Alert> alerts = new ArrayList<>();
    for (List<OrderEvent> group : groups.values()) {
      group.sort(Comparator.comparing(OrderEvent::eventTime));
      for (int start = 0; start < group.size(); start++) {
        List<OrderEvent> window = new ArrayList<>();
        OrderEvent first = group.get(start);
        for (int cursor = start; cursor < group.size(); cursor++) {
          OrderEvent candidate = group.get(cursor);
          long seconds = Duration.between(first.eventTime(), candidate.eventTime()).toSeconds();
          if (seconds <= 90) {
            window.add(candidate);
          }
        }
        if (window.size() < 5) {
          continue;
        }
        long sameSideCount = window.stream().filter(event -> event.side().equals(first.side())).count();
        if (sameSideCount < 5) {
          continue;
        }
        double firstPrice = window.getFirst().price();
        double lastPrice = window.getLast().price();
        double priceMovePct = "BUY".equals(first.side())
            ? ((lastPrice - firstPrice) / firstPrice) * 100
            : ((firstPrice - lastPrice) / firstPrice) * 100;
        int totalQty = window.stream().mapToInt(OrderEvent::quantity).sum();
        if (priceMovePct < 1.0 || totalQty < 30_000) {
          continue;
        }
        long windowSeconds = Math.max(1, Duration.between(window.getFirst().eventTime(), window.getLast().eventTime()).toSeconds());
        TraderSymbolStats stats = baseline.statsFor(first.traderId(), first.symbol());
        int score = Math.min(97, (int) (62 + priceMovePct * 8 + window.size() * 3 + Math.min(totalQty / 10_000, 8)));
        alerts.add(new Alert(
            "AM-%04d".formatted(alerts.size() + 1),
            "Momentum Ignition / Price Spike Manipulation",
            first.traderId(),
            first.accountId(),
            first.symbol(),
            score >= 85 ? "HIGH" : "MEDIUM",
            score,
            Map.of(
                "aggressiveExecutions", window.size(),
                "dominantSide", first.side(),
                "windowSeconds", windowSeconds,
                "priceMovePct", round(priceMovePct, 2),
                "totalExecutedQuantity", totalQty,
                "baselineMaxPriceMovePct", round(stats.maxPriceMovePct(), 2),
                "avgExecutionQuantity", Math.round(stats.averageExecutionQuantity())),
            window.stream().limit(10).toList(),
            Instant.now()));
        break;
      }
    }
    return alerts;
  }

  private long countSide(List<CancelledOrder> orders, String side) {
    return orders.stream().filter(item -> side.equals(item.newEvent().side())).count();
  }

  private double median(List<Double> values) {
    int size = values.size();
    if (size % 2 == 1) {
      return values.get(size / 2);
    }
    return (values.get(size / 2 - 1) + values.get(size / 2)) / 2.0;
  }

  private double round(double value, int places) {
    double factor = Math.pow(10, places);
    return Math.round(value * factor) / factor;
  }

  private record CancelledOrder(OrderEvent newEvent, OrderEvent cancel, double ageMs) {}
  private record WashCycle(OrderEvent left, OrderEvent right, long seconds) {}
}
