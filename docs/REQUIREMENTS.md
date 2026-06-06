# Trade Sentinel — Requirements & Phase Specification

> This document records the exact requirements and phase breakdown provided by the product owner.  
> Primary source of truth: `docs/imlpl-2.md`  
> Reference document: `docs/IMPLEMENTATION_PLAN.md`

---

## Architecture Constraints

**Stack**: Spring Boot + PostgreSQL + Thymeleaf + Claude API  
**Java**: 21  
**Build**: Gradle  
**Migrations**: Flyway  

### Explicitly excluded (do NOT build)
| Item | Reason |
|------|--------|
| Kafka | Low scoring value, high implementation cost |
| Redis | Low scoring value, high implementation cost |
| XGBoost / IsolationForest | Phase 3 — out of scope for MVP |
| Real Jira Integration | Too much effort for limited scoring benefit |
| ServiceNow | Same as Jira |
| Microservices | Architecture must remain monolith |

---

## High Priority Requirements

| # | Requirement |
|---|-------------|
| H1 | Main window to display trades (separate Live Trades tab) — real-time |
| H2 | PostgreSQL persistence for alerts + triage |
| H3 | `ANTHROPIC_API_KEY` injection via env var (remove from code) |
| H4 | Re-enable and tune SPOOFING single-order detector |
| H5 | Rate limiting on Claude API calls (token bucket) |
| H6 | Spring Boot Actuator health + metrics endpoints |
| H7 | JSON ingestion endpoint (`POST /api/replay-json`) |
| H8 | Global exception handler with structured error responses |
| H9 | ML pre-filter (IsolationForest + XGBoost) to reduce Claude calls *(deferred to Phase 3)* |
| H10 | Triage quality improvement — trader baseline JSON file alongside orders |

### Triage Quality Spec (H10)
- Detect real alert vs false positive
- Human-readable verdict with confidence ratio
- When Claude can see "this trader's normal cancel ratio is 12% but today it's 87%" the rationale is dramatically more specific

---

## Medium Priority Requirements

| # | Requirement |
|---|-------------|
| M1 | Real-time mock data push to dashboard (using a scheduler) |
| M2 | Kafka consumer for live order event ingestion *(excluded — architecture constraint)* |
| M3 | Redis for per-trader sliding-window state *(excluded — architecture constraint)* |
| M4 | 30-day historical baseline from data warehouse |
| M5 | Front-running detector |
| M6 | Wash-trading cross-account (beneficial owner) enrichment |
| M7 | Jira / ServiceNow case creation webhook *(excluded — architecture constraint)* |
| M8 | Slack / PagerDuty notifications |
| M9 | Email notifications (`rohan.bhalerao@wissen.com` as recipient) |

---

## Phase Breakdown (as specified by product owner)

### Phase 1 — Project Foundation
**Goal**: PostgreSQL, Entities, Repositories

Implement:
1. Gradle Configuration (Java 21)
2. Application Configuration
3. Flyway Setup
4. PostgreSQL Integration
5. Environment Variable Configuration
6. Domain Entities (7 total)
7. JPA Repositories

**Entities required**:
- `OrderEvent`
- `SurveillanceAlert`
- `TriageResult`
- `ComplianceCase`
- `Notification`
- `WatchlistEntry`
- `AuditEvent`

**Requirements**: Proper relationships, JPA annotations, auditing support, repository layer  
**Also generate**: Initial Flyway migrations, `application.properties`, `.env.example`, `.gitignore`  
**Do NOT generate**: Services, Controllers, Scheduler, Claude Integration  
**Gate**: Run build validation and stop.

---

### Phase 2 — Trade Generation + Detection Engine
**Goal**: Scheduler, Detection

Implement:
1. Scenario Scheduler
2. Synthetic Trade Generator
3. CSV Upload
4. Replay Engine
5. Detection Engine

**Scenarios** (with distribution):
| Scenario | Distribution |
|----------|-------------|
| Normal Trading | 90% |
| Layering | 3% |
| Spoofing | 2% |
| Wash Trading | 2% |
| Front Running | 1% |
| Momentum Ignition | 1% |
| Pump & Dump | 1% |

**Detectors**:
- Layering Detector
- Spoofing Detector
- Wash Trading Detector
- Front Running Detector *(new — not in original MVP)*

**Detection Output**: Create `SurveillanceAlert` records in DB  
**Gate**: Verify alerts are generated automatically. Stop.

---

### Phase 3 — Claude Triage Engine
**Goal**: AI-powered triage

Implement:
1. Claude Client
2. Prompt Templates
3. Alert Review Service
4. Triage Result Storage

**Rules**:
- Only `HIGH` and `CRITICAL` alerts go to Claude
- Never send: trades, replay data, raw market data directly
- Flow: `Trades → Detection → Alert → Claude`

**Claude returns**:
- Verdict
- Confidence
- False Positive Probability
- Reasoning
- Recommended Action

**Properties**:
```properties
anthropic.high-severity-only=true
anthropic.max-alerts-per-run=100
```

**Gate**: Verify alert triage works. Stop.

---

### Phase 4 — Dashboard
**Goal**: Full Thymeleaf dashboard

**Pages**:
- Live Trades
- Alerts
- Cases
- Watchlist
- Audit Trail

**Charts**:
- Alert Distribution (Bar)
- Verdict Distribution (Pie)
- Escalation Distribution (Pie)
- Risk Trend (Line)

**Explainability Panel** — Every alert must display:
- Pattern
- Evidence
- Metrics
- Risk Score
- Claude Reasoning
- Escalation Decision

**Manual Buttons** (for judge demo — guaranteed deterministic):
- Generate Layering Attack
- Generate Spoofing Attack
- Generate Wash Trading
- Generate Front Running
- Generate Pump & Dump

**Acceptance**: Clicking a button generates data that results in an alert within 10 seconds.  
**Gate**: End-to-end demo flow works. Stop.

---

### Phase 5 — Notifications
**Goal**: Email + Slack

Implement:
1. Email Notification Service
2. Slack Notification Service

**Environment Variables**:
```
MAIL_USERNAME
MAIL_PASSWORD
SLACK_WEBHOOK_URL
```

Until credentials are provided: use mock implementations.  
Design: real credentials can be added later without code changes.  
Persist notifications in database.  
**Gate**: Verify notification records are generated. Stop.

---

## Risk Score Engine Specification

```
Risk Score = 30% Detection Confidence
           + 25% Baseline Deviation
           + 25% Claude Confidence
           + 20% Historical Trader Risk

Range: 0 – 100
```

---

## Escalation Matrix

| Score | Verdict | Automatic Action |
|-------|---------|-----------------|
| 0 – 40 | FALSE_POSITIVE | Ignore |
| 41 – 70 | REVIEW | Analyst Queue |
| 71 – 85 | REAL_ALERT | Create Compliance Case |
| 86 – 95 | REAL_ALERT | Case + Notification |
| 96 – 100 | REAL_ALERT | Case + Notification + Watchlist |

*Display this table in the UI.*

---

## Claude Cost Protection

**Budget**: ~$25  
**Rules**: Only HIGH and CRITICAL alerts reach Claude. LOW and MEDIUM use offline triage.

```properties
anthropic.enabled=true
anthropic.max-alerts-per-run=100
anthropic.max-daily-alert-reviews=500
anthropic.high-severity-only=true
anthropic.max-concurrent-requests=2
```

**Expected flow**: 10,000 Trades → 100 Alerts → ~20 Claude Calls (safe for budget)

---

## Judging Score Matrix

| Category | Weight |
|----------|--------|
| AI Triage Quality | 25% |
| Pattern Detection | 20% |
| Automation & Workflow | 20% |
| Working Demo | 20% |
| API Efficiency | 10% |
| Documentation | 5% |

**Optimization goal**: Maximize AI Triage + Automation + Demo = **65% of total score**

---

## Final Demo Flow (for judges)

1. Start Replay
2. Trades Appear on Live Trades tab
3. Suspicious Scenario Generated (button or auto)
4. Detection Engine Creates Alert
5. Claude Reviews Alert
6. Risk Score Calculated
7. Escalation Matrix Evaluated
8. Case Created
9. Email/Slack Notification Generated
10. Trader Added To Watchlist
11. Audit Trail Updated

**Judge sees**: Ingestion → Detection → Triage → Escalation
