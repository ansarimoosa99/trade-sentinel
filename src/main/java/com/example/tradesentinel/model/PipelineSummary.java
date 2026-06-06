package com.example.tradesentinel.model;

public record PipelineSummary(
    int eventsIngested,
    int alertsGenerated,
    int highSeverity,
    int escalated,
    int review,
    int ignored,
    String triageSource) {}
