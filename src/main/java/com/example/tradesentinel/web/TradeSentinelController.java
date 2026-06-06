package com.example.tradesentinel.web;

import com.example.tradesentinel.model.*;
import com.example.tradesentinel.service.*;
import com.example.tradesentinel.service.SyntheticTradeGenerator.Scenario;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TradeSentinelController {

    private final PipelineService pipelineService;
    private final TriageService triageService;
    private final JsonOrderParser jsonOrderParser;

    // Optional — not present when scheduler.enabled=false
    @Autowired(required = false)
    private ScenarioScheduler scenarioScheduler;

    public TradeSentinelController(PipelineService pipelineService,
                                    TriageService triageService,
                                    JsonOrderParser jsonOrderParser) {
        this.pipelineService = pipelineService;
        this.triageService = triageService;
        this.jsonOrderParser = jsonOrderParser;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(true, triageService.claudeConfigured());
    }

    @GetMapping("/sample")
    public SampleResponse sample() throws IOException {
        return new SampleResponse(sampleCsv());
    }

    @GetMapping("/sample-json")
    public ResponseEntity<String> sampleJson() throws IOException {
        ClassPathResource resource = new ClassPathResource("trade_data.json");
        if (!resource.exists()) return ResponseEntity.notFound().build();
        String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return ResponseEntity.ok().header("Content-Type", "application/json").body(content);
    }

    @PostMapping("/replay")
    public ResponseEntity<?> replay(@RequestBody(required = false) ReplayRequest request) throws IOException {
        String csv = request != null && request.csv() != null && !request.csv().isBlank()
                ? request.csv() : sampleCsv();
        int delay = request != null && request.replayDelayMs() != null
                ? Math.max(0, request.replayDelayMs()) : 0;
        return ResponseEntity.ok(pipelineService.run(csv, delay));
    }

    @PostMapping("/replay-json")
    public ResponseEntity<?> replayJson(@RequestBody(required = false) String jsonBody) throws IOException {
        String json = jsonBody != null && !jsonBody.isBlank() ? jsonBody : sampleJsonString();
        List<OrderEvent> events = jsonOrderParser.parse(json);
        return ResponseEntity.ok(pipelineService.runFromEvents(events, 0));
    }

    // ── Live feed (used by dashboard Trades tab) ──────────────────────────────

    @GetMapping("/trades/live")
    public ResponseEntity<List<OrderEvent>> liveTrades() {
        if (scenarioScheduler == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(scenarioScheduler.getLiveFeed());
    }

    // ── Manual scenario triggers ───────────────────────────────────────────────

    @PostMapping("/trigger/{scenario}")
    public ResponseEntity<Map<String, Object>> triggerScenario(@PathVariable String scenario) {
        if (scenarioScheduler == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Scheduler not enabled — set scheduler.enabled=true"));
        }
        Scenario sc;
        try {
            sc = Scenario.valueOf(scenario.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown scenario: " + scenario));
        }
        var result = scenarioScheduler.triggerScenario(sc);
        var s = result.summary();
        return ResponseEntity.ok(Map.of(
                "status",       "triggered",
                "scenario",     sc.name(),
                "totalEvents",  s.eventsIngested(),
                "alertCount",   s.alertsGenerated(),
                "highSeverity", s.highSeverity(),
                "escalated",    s.escalated(),
                "review",       s.review(),
                "ignored",      s.ignored(),
                "triageMode",   s.triageSource()
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String sampleCsv() throws IOException {
        ClassPathResource resource = new ClassPathResource("sample_orders.csv");
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    private String sampleJsonString() throws IOException {
        ClassPathResource resource = new ClassPathResource("trade_data.json");
        if (!resource.exists()) throw new IllegalArgumentException("No trade_data.json on classpath");
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}
