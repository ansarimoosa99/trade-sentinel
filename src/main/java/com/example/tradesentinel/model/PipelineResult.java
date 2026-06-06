package com.example.tradesentinel.model;

import java.util.List;

public record PipelineResult(
    PipelineSummary summary,
    List<OrderEvent> events,
    List<TriagedAlert> alerts,
    List<EscalationResult> escalations) {}
