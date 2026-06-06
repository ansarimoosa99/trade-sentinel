package com.example.tradesentinel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import com.example.tradesentinel.model.PipelineResult;
import com.fasterxml.jackson.databind.ObjectMapper;

class PipelineServiceTest {
  private final PipelineService pipelineService = new PipelineService(
      new CsvOrderParser(),
      new DetectionService(new BaselineService()),
      new TriageService(new ObjectMapper(), false, null, null, true, 5.0));

  @Test
  void sampleReplayProducesJudgeReadySummary() throws Exception {
    PipelineResult result = pipelineService.run(sampleCsv(), 0);

    assertThat(result.summary().eventsIngested()).isEqualTo(37);
    assertThat(result.summary().alertsGenerated()).isEqualTo(3);
    assertThat(result.summary().escalated()).isEqualTo(3);
    assertThat(result.summary().ignored()).isZero();
    assertThat(result.escalations()).allSatisfy(escalation -> assertThat(escalation.actions()).hasSize(3));
  }

  private String sampleCsv() throws Exception {
    return StreamUtils.copyToString(new ClassPathResource("sample_orders.csv").getInputStream(),
        StandardCharsets.UTF_8);
  }
}
