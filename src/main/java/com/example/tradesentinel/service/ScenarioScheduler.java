package com.example.tradesentinel.service;

import com.example.tradesentinel.model.OrderEvent;
import com.example.tradesentinel.service.SyntheticTradeGenerator.Scenario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = false)
public class ScenarioScheduler {

    private static final int LIVE_FEED_MAX = 200;

    private final SyntheticTradeGenerator generator;
    private final PipelineService pipelineService;
    private final PersistenceService persistenceService;

    // Ring buffer used by /api/trades/live endpoint
    private final ConcurrentLinkedDeque<OrderEvent> liveFeed = new ConcurrentLinkedDeque<>();

    @Scheduled(fixedDelayString = "${scheduler.interval-ms:3000}")
    public void tick() {
        try {
            Scenario scenario = generator.pickScenario();
            List<OrderEvent> events = generator.generate(scenario);
            String runId = "SCHED-" + UUID.randomUUID().toString().substring(0, 8);

            // Persist events to DB
            persistenceService.saveOrderEvents(events, runId);

            // Feed live display (keep newest 200 entries)
            events.forEach(e -> {
                liveFeed.addFirst(e);
                if (liveFeed.size() > LIVE_FEED_MAX) liveFeed.pollLast();
            });

            // Run detection + triage + escalation through the pipeline
            pipelineService.runFromEvents(events, 0);

            if (scenario != Scenario.NORMAL) {
                log.info("Scheduler generated scenario: {} ({} events)", scenario, events.size());
            }
        } catch (Exception ex) {
            log.error("Scheduler tick failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Manually trigger a specific scenario (called from dashboard buttons).
     */
    public com.example.tradesentinel.model.PipelineResult triggerScenario(Scenario scenario) {
        List<OrderEvent> events = generator.generate(scenario);
        String runId = "MANUAL-" + UUID.randomUUID().toString().substring(0, 8);
        persistenceService.saveOrderEvents(events, runId);
        events.forEach(e -> {
            liveFeed.addFirst(e);
            if (liveFeed.size() > LIVE_FEED_MAX) liveFeed.pollLast();
        });
        com.example.tradesentinel.model.PipelineResult result = pipelineService.runFromEvents(events, 0);
        log.info("Manual scenario triggered: {} ({} events, {} alerts)", scenario, events.size(), result.summary().alertsGenerated());
        return result;
    }

    public List<OrderEvent> getLiveFeed() {
        return List.copyOf(liveFeed);
    }
}
