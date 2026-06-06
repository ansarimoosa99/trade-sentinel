package com.example.tradesentinel.model;

import java.util.List;

public record EscalationResult(String alertId, List<ActionResult> actions) {}
