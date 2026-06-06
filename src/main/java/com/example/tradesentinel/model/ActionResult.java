package com.example.tradesentinel.model;

import java.time.Instant;

public record ActionResult(
    String id,
    String type,
    String target,
    String status,
    String owner,
    String priority,
    String sla,
    Instant createdAt) {}
