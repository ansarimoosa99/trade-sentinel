package com.example.tradesentinel.web;

import com.example.tradesentinel.model.HealthResponse;
import com.example.tradesentinel.model.OrderEvent;
import com.example.tradesentinel.model.PipelineResult;
import com.example.tradesentinel.model.ReplayRequest;
import com.example.tradesentinel.model.SampleResponse;
import com.example.tradesentinel.service.JsonOrderParser;
import com.example.tradesentinel.service.PipelineService;
import com.example.tradesentinel.service.TriageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TradeSentinelController {
  private final PipelineService pipelineService;
  private final TriageService triageService;
  private final JsonOrderParser jsonOrderParser;

  public TradeSentinelController(
      PipelineService pipelineService,
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
    if (!resource.exists()) {
      return ResponseEntity.notFound().build();
    }
    String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    return ResponseEntity.ok().header("Content-Type", "application/json").body(content);
  }

  @PostMapping("/replay")
  public ResponseEntity<?> replay(@RequestBody(required = false) ReplayRequest request) throws IOException {
    String csv = request != null && request.csv() != null && !request.csv().isBlank()
        ? request.csv()
        : sampleCsv();
    int delay = request != null && request.replayDelayMs() != null
        ? Math.max(0, request.replayDelayMs())
        : 0;
    PipelineResult result = pipelineService.run(csv, delay);
    return ResponseEntity.ok(result);
  }

  @PostMapping("/replay-json")
  public ResponseEntity<?> replayJson(@RequestBody(required = false) String jsonBody) throws IOException {
    String json = jsonBody != null && !jsonBody.isBlank() ? jsonBody : sampleJsonString();
    List<OrderEvent> events = jsonOrderParser.parse(json);
    PipelineResult result = pipelineService.runFromEvents(events, 0);
    return ResponseEntity.ok(result);
  }

  private String sampleCsv() throws IOException {
    ClassPathResource resource = new ClassPathResource("sample_orders.csv");
    return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
  }

  private String sampleJsonString() throws IOException {
    ClassPathResource resource = new ClassPathResource("trade_data.json");
    if (!resource.exists()) {
      throw new IllegalArgumentException("No trade_data.json found on classpath; provide a JSON body");
    }
    return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
  }
}
