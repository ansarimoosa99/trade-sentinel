package com.example.tradesentinel.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TriageResult(
    String verdict,
    int confidence,
    @JsonProperty("false_positive_probability") int falsePositiveProbability,
    String reason,
    List<String> riskFactors,
    List<String> falsePositiveFactors,
    List<String> recommendedActions,
    String caseNote,
    String source) {}
