package com.example.tradesentinel.web;

import com.example.tradesentinel.model.HealthResponse;
import com.example.tradesentinel.model.PipelineResult;
import com.example.tradesentinel.model.ReplayRequest;
import com.example.tradesentinel.model.SampleResponse;
import com.example.tradesentinel.service.PipelineService;
import com.example.tradesentinel.service.TriageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

  public TradeSentinelController(PipelineService pipelineService, TriageService triageService) {
    this.pipelineService = pipelineService;
    this.triageService = triageService;
  }

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse(true, triageService.claudeConfigured());
  }

  @GetMapping("/sample")
  public SampleResponse sample() throws IOException {
    return new SampleResponse(sampleCsv());
  }

  @PostMapping("/replay")
  public ResponseEntity<?> replay(@RequestBody(required = false) ReplayRequest request) throws IOException {
    try {
      String csv = request != null && request.csv() != null && !request.csv().isBlank() ? request.csv() : sampleCsv();
      int delay = request != null && request.replayDelayMs() != null ? Math.max(0, request.replayDelayMs()) : 0;
      PipelineResult result = pipelineService.run(csv, delay);
      return ResponseEntity.ok(result);
    } catch (RuntimeException exception) {
      return ResponseEntity.badRequest().body(java.util.Map.of("error", exception.getMessage()));
    }
  }

  private String sampleCsv() throws IOException {
    ClassPathResource resource = new ClassPathResource("sample_orders.csv");
    return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
  }
}
