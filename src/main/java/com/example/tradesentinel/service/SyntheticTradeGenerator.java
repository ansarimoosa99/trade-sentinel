package com.example.tradesentinel.service;

import com.example.tradesentinel.model.OrderEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates realistic synthetic OrderEvent batches per scenario.
 * Distribution: Normal 90%, Layering 3%, Spoofing 2%, WashTrading 2%,
 *               FrontRunning 1%, MomentumIgnition 1%, PumpAndDump 1%.
 */
@Service
public class SyntheticTradeGenerator {

    private static final String[] SYMBOLS = {"RELIANCE", "TCS", "HDFC", "INFY", "ICICIBANK", "WIPRO", "AXISBANK", "SBIN"};
    private static final String[] TRADERS = {"T-001", "T-002", "T-003", "T-004", "T-005", "T-006", "T-007", "T-008"};
    private static final String[] EXCHANGES = {"NSE", "BSE"};

    private final Random rng = new Random();

    public enum Scenario {
        NORMAL, LAYERING, SPOOFING, WASH_TRADING, FRONT_RUNNING, MOMENTUM_IGNITION, PUMP_AND_DUMP
    }

    public Scenario pickScenario() {
        // Tuned for a 3-minute demo (60 ticks × 3 s):
        //   ~3 attack ticks → ~3 new alerts → ~2-3 compliance cases
        int roll = rng.nextInt(100);
        if (roll < 95) return Scenario.NORMAL;              // 95%
        if (roll < 97) return Scenario.LAYERING;            // 2%  → P0 case + notify + watchlist
        if (roll < 98) return Scenario.WASH_TRADING;        // 1%  → P1 case + notify
        if (roll < 99) return Scenario.SPOOFING;            // 1%  → P1 case
        return Scenario.MOMENTUM_IGNITION;                  // 1%  → P1 case
        // FRONT_RUNNING and PUMP_AND_DUMP available via manual trigger buttons
    }

    public List<OrderEvent> generate(Scenario scenario) {
        return switch (scenario) {
            case NORMAL -> generateNormal();
            case LAYERING -> generateLayering();
            case SPOOFING -> generateSpoofing();
            case WASH_TRADING -> generateWashTrading();
            case FRONT_RUNNING -> generateFrontRunning();
            case MOMENTUM_IGNITION -> generateMomentumIgnition();
            case PUMP_AND_DUMP -> generatePumpAndDump();
        };
    }

    // ── Scenario generators ──────────────────────────────────────────────────

    private List<OrderEvent> generateNormal() {
        List<OrderEvent> events = new ArrayList<>();
        String trader = randomTrader();
        String symbol = randomSymbol();
        double basePrice = randomBasePrice();
        Instant now = Instant.now();

        for (int i = 0; i < rng.nextInt(5) + 3; i++) {
            String orderId = newOrderId();
            String side = rng.nextBoolean() ? "BUY" : "SELL";
            events.add(event(orderId, trader, symbol, side, randomQty(500, 3000), basePrice + rng.nextGaussian() * 2, "NEW", now.plusMillis(i * 200L)));
            if (rng.nextDouble() < 0.85) {
                events.add(event(orderId, trader, symbol, side, randomQty(500, 3000), basePrice + rng.nextGaussian() * 2, "EXECUTE", now.plusMillis(i * 200L + 100)));
            } else {
                events.add(event(orderId, trader, symbol, side, randomQty(500, 3000), basePrice, "CANCEL", now.plusMillis(i * 200L + 300)));
            }
        }
        return events;
    }

    private List<OrderEvent> generateLayering() {
        List<OrderEvent> events = new ArrayList<>();
        String trader = randomTrader();
        String symbol = randomSymbol();
        double basePrice = randomBasePrice();
        Instant now = Instant.now();

        // Place 8 large BUY orders and cancel them rapidly (layering on bid side)
        for (int i = 0; i < 8; i++) {
            String orderId = newOrderId();
            events.add(event(orderId, trader, symbol, "BUY", randomQty(30000, 60000), basePrice - i * 0.5, "NEW", now.plusMillis(i * 50L)));
            events.add(event(orderId, trader, symbol, "BUY", randomQty(30000, 60000), basePrice - i * 0.5, "CANCEL", now.plusMillis(i * 50L + 400)));
        }
        // Small SELL executions during the layering window (the actual intent)
        for (int i = 0; i < 3; i++) {
            String execId = newOrderId();
            events.add(event(execId, trader, symbol, "SELL", randomQty(2000, 5000), basePrice + 1, "NEW", now.plusMillis(200 + i * 100L)));
            events.add(event(execId, trader, symbol, "SELL", randomQty(2000, 5000), basePrice + 1, "EXECUTE", now.plusMillis(250 + i * 100L)));
        }
        return events;
    }

    private List<OrderEvent> generateSpoofing() {
        List<OrderEvent> events = new ArrayList<>();
        String trader = randomTrader();
        String symbol = randomSymbol();
        double basePrice = randomBasePrice();
        Instant now = Instant.now();

        // Very large order placed and cancelled within 300 ms (spoof)
        for (int i = 0; i < 4; i++) {
            String orderId = newOrderId();
            events.add(event(orderId, trader, symbol, "BUY", randomQty(50000, 100000), basePrice + i, "NEW", now.plusMillis(i * 500L)));
            events.add(event(orderId, trader, symbol, "BUY", randomQty(50000, 100000), basePrice + i, "CANCEL", now.plusMillis(i * 500L + 250)));
        }
        return events;
    }

    private List<OrderEvent> generateWashTrading() {
        List<OrderEvent> events = new ArrayList<>();
        String trader = randomTrader();
        String symbol = randomSymbol();
        double basePrice = randomBasePrice();
        Instant now = Instant.now();

        // Repeated buy/sell cycles with near-matched quantities
        for (int i = 0; i < 5; i++) {
            int qty = randomQty(5000, 10000);
            String buyId = newOrderId();
            String sellId = newOrderId();
            events.add(event(buyId, trader, symbol, "BUY", qty, basePrice, "NEW", now.plusMillis(i * 70000L)));
            events.add(event(buyId, trader, symbol, "BUY", qty, basePrice, "EXECUTE", now.plusMillis(i * 70000L + 100)));
            events.add(event(sellId, trader, symbol, "SELL", qty, basePrice, "NEW", now.plusMillis(i * 70000L + 20000)));
            events.add(event(sellId, trader, symbol, "SELL", qty, basePrice, "EXECUTE", now.plusMillis(i * 70000L + 20100)));
        }
        return events;
    }

    private List<OrderEvent> generateFrontRunning() {
        List<OrderEvent> events = new ArrayList<>();
        String aggressiveTrader = randomTrader();
        String institutionalTrader = differentTrader(aggressiveTrader);
        String symbol = randomSymbol();
        double basePrice = randomBasePrice();
        Instant now = Instant.now();

        // Aggressive trader buys first, then large institutional order arrives
        String frontRunId = newOrderId();
        events.add(event(frontRunId, aggressiveTrader, symbol, "BUY", randomQty(10000, 20000), basePrice, "NEW", now));
        events.add(event(frontRunId, aggressiveTrader, symbol, "BUY", randomQty(10000, 20000), basePrice, "EXECUTE", now.plusMillis(100)));

        String instId = newOrderId();
        events.add(event(instId, institutionalTrader, symbol, "BUY", randomQty(200000, 500000), basePrice, "NEW", now.plusMillis(200)));
        events.add(event(instId, institutionalTrader, symbol, "BUY", randomQty(200000, 500000), basePrice + 0.5, "EXECUTE", now.plusMillis(350)));

        // Aggressive trader sells into price impact
        String exitId = newOrderId();
        events.add(event(exitId, aggressiveTrader, symbol, "SELL", randomQty(10000, 20000), basePrice + 1.5, "NEW", now.plusMillis(500)));
        events.add(event(exitId, aggressiveTrader, symbol, "SELL", randomQty(10000, 20000), basePrice + 1.5, "EXECUTE", now.plusMillis(600)));
        return events;
    }

    private List<OrderEvent> generateMomentumIgnition() {
        List<OrderEvent> events = new ArrayList<>();
        String trader = randomTrader();
        String symbol = randomSymbol();
        double basePrice = randomBasePrice();
        Instant now = Instant.now();

        // Rapid aggressive buys driving price up — 1.5% per step so priceMovePct > 1% regardless of base
        for (int i = 0; i < 8; i++) {
            String orderId = newOrderId();
            double price = basePrice * (1.0 + i * 0.015);
            events.add(event(orderId, trader, symbol, "BUY", randomQty(8000, 15000), price, "NEW", now.plusMillis(i * 10000L)));
            events.add(event(orderId, trader, symbol, "BUY", randomQty(8000, 15000), price, "EXECUTE", now.plusMillis(i * 10000L + 100)));
        }
        return events;
    }

    private List<OrderEvent> generatePumpAndDump() {
        List<OrderEvent> events = new ArrayList<>();
        String trader = randomTrader();
        String symbol = randomSymbol();
        double basePrice = randomBasePrice();
        Instant now = Instant.now();

        // Pump: aggressive buys — 2% per step so priceMovePct is always well above threshold
        for (int i = 0; i < 6; i++) {
            String orderId = newOrderId();
            double price = basePrice * (1.0 + i * 0.02);
            events.add(event(orderId, trader, symbol, "BUY", randomQty(20000, 40000), price, "NEW", now.plusMillis(i * 8000L)));
            events.add(event(orderId, trader, symbol, "BUY", randomQty(20000, 40000), price, "EXECUTE", now.plusMillis(i * 8000L + 100)));
        }
        // Dump: rapid sells at elevated price
        for (int i = 0; i < 5; i++) {
            String orderId = newOrderId();
            double price = basePrice * (1.10 - i * 0.015);
            events.add(event(orderId, trader, symbol, "SELL", randomQty(20000, 40000), price, "NEW", now.plusMillis(50000 + i * 5000L)));
            events.add(event(orderId, trader, symbol, "SELL", randomQty(20000, 40000), price, "EXECUTE", now.plusMillis(50000 + i * 5000L + 100)));
        }
        return events;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private OrderEvent event(String orderId, String trader, String symbol,
                              String side, int qty, double price, String type, Instant time) {
        return new OrderEvent(orderId, trader, "ACC-" + trader, symbol,
                EXCHANGES[rng.nextInt(EXCHANGES.length)], side, qty, price, type, time);
    }

    private String newOrderId() { return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }
    private String randomTrader() { return TRADERS[rng.nextInt(TRADERS.length)]; }
    private String differentTrader(String exclude) {
        String t;
        do { t = TRADERS[rng.nextInt(TRADERS.length)]; } while (t.equals(exclude));
        return t;
    }
    private String randomSymbol() { return SYMBOLS[rng.nextInt(SYMBOLS.length)]; }
    private double randomBasePrice() { return 100 + rng.nextInt(4900) + rng.nextDouble(); }
    private int randomQty(int min, int max) { return min + rng.nextInt(max - min); }
}
