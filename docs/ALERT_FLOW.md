# Alert Flow — Complete Implementation Reference

This document describes exactly how a trade event becomes an alert, how that alert gets triaged, how a risk score is computed, and how the system decides whether to create a compliance case, send a notification, or add a trader to the watchlist.

---

## Table of Contents

1. [High-Level Pipeline](#1-high-level-pipeline)
2. [Step 1 — Event Ingestion](#2-step-1--event-ingestion)
3. [Step 2 — Detection (5 Algorithms)](#3-step-2--detection-5-algorithms)
4. [Step 3 — Baseline Analysis](#4-step-3--baseline-analysis)
5. [Step 4 — AI Triage](#5-step-4--ai-triage)
6. [Step 5 — Risk Scoring](#6-step-5--risk-scoring)
7. [Step 6 — Escalation Decision](#7-step-6--escalation-decision)
8. [Step 7 — Compliance Cases](#8-step-7--compliance-cases)
9. [Step 8 — Notifications](#9-step-8--notifications)
10. [Step 9 — Watchlist](#10-step-9--watchlist)
11. [Step 10 — Audit Trail](#11-step-10--audit-trail)
12. [Entity Relationships](#12-entity-relationships)
13. [Score Math Reference](#13-score-math-reference)
14. [Event Type Reference](#14-event-type-reference)
15. [Code Map](#15-code-map)

---

## 1. High-Level Pipeline

```
OrderEvent[]
     │
     ▼
 BaselineService.analyze()          ← computes per-trader cancel ratios, qty, price move
     │
     ▼
 DetectionService.detectAlerts()    ← 5 pattern detectors in parallel
     │
     ▼  (one or more Alert objects, or nothing for clean batches)
 TriageService.triage()             ← Claude AI or offline rule engine, per alert
     │
     ▼  (TriageResult: verdict, confidence, case note, etc.)
 EscalationService.process()
     ├── RiskScoreEngine.compute()  ← 0–100 composite score
     ├── RiskScoreEngine.decide()   ← IGNORE / REVIEW / CASE / CASE_AND_NOTIFY / CASE_NOTIFY_WATCHLIST
     └── switch(decision)
             ├── IGNORE           → audit ALERT_IGNORED
             ├── REVIEW           → audit ALERT_REVIEWED  (no case created)
             ├── CASE             → compliance_cases (P1)
             ├── CASE_AND_NOTIFY  → compliance_cases (P1) + email + Slack
             └── CASE_NOTIFY_WATCHLIST → compliance_cases (P0) + email + Slack + watchlist_entries
```

Everything is written to PostgreSQL. The dashboard reads from the same database.

---

## 2. Step 1 — Event Ingestion

**Source A — Scheduler** (`ScenarioScheduler.java`)

The scheduler fires every 3 seconds (`scheduler.interval-ms`). Each tick:

1. Calls `SyntheticTradeGenerator.pickScenario()` — weighted random draw:

   | Scenario | Probability |
   |---|---|
   | NORMAL | 93% |
   | LAYERING | 2% |
   | WASH_TRADING | 2% |
   | SPOOFING | 1% |
   | FRONT_RUNNING | 1% |
   | MOMENTUM_IGNITION | 1% |
   | PUMP_AND_DUMP | manual trigger only |

2. Calls `SyntheticTradeGenerator.generate(scenario)` → `List<OrderEvent>`.
3. Persists events to `order_events` table via `PersistenceService.saveOrderEvents()`.
4. Adds events to the live feed ring buffer (200 entries max, newest first).
5. Passes events to `PipelineService.runFromEvents()`.

**Source B — CSV/JSON upload** (`TradeSentinelController.java`)

`POST /api/replay` → `CsvOrderParser.parse()` → same `PipelineService.runFromEvents()`.

**`OrderEvent` record fields:**

```
orderId     — unique order identifier (ORD-XXXXXXXX)
traderId    — trader code (T-001 … T-008)
accountId   — account code (ACC-T-001 …)
symbol      — instrument (RELIANCE, TCS, HDFC, …)
exchange    — NSE or BSE
side        — BUY or SELL
quantity    — integer share count
price       — double (₹)
eventType   — NEW | MODIFY | CANCEL | EXECUTE
eventTime   — Instant (UTC)
```

---

## 3. Step 2 — Detection (5 Algorithms)

All five detectors run inside `DetectionService.detectAlerts()` and receive the same batch of `OrderEvent` objects and the pre-computed `SurveillanceBaseline`. They are independent — a single batch can produce zero, one, or multiple alerts.

### 3.1 Layering / Spoofing Detector

**What it looks for:** A trader places a large number of large orders on one side and cancels them rapidly while simultaneously executing smaller orders on the opposite side — artificially moving the visible order book to benefit their real trades.

**Algorithm:**
1. Group all `CANCEL` events by `traderId | symbol`.
2. For each cancel, find the matching `NEW` event and compute age in milliseconds.
3. Filter to only orders with `quantity ≥ 25,000`.
4. **Trigger conditions** (both must be true):
   - `largeCancelledOrders ≥ 6`
   - `cancelRatio ≥ 0.70` AND `medianCancelTimeMs ≤ 1,500 ms`
5. Count opposite-side `EXECUTE` events during the same window.

**Score formula:**

```
score = min(99,  55
           + cancelRatio × 25
           + largeCancelledOrders × 2
           + min(oppositeSideExecutions, 5) × 3)
```

| cancelRatio | largeOrders | oppExecs | Score |
|---|---|---|---|
| 1.0 | 8 | 3 | 99 (capped) |
| 0.8 | 6 | 0 | 87 |
| 0.7 | 6 | 0 | 82 |

**Severity:** `HIGH` if score ≥ 85, otherwise `MEDIUM`.

---

### 3.2 Spoofing Detector

**What it looks for:** Individual large orders that are placed and cancelled before execution, with no fill, within a very short window — the classic "place-and-pull" spoof.

**Algorithm:**
1. Build maps of `NEW`, `CANCEL`, and executed order IDs.
2. For each cancelled order that was never executed and `cancelMs ∈ [0, 2000]`:
   - Check if `quantity ≥ 5 × avgExecutionQuantity` (baseline-relative size threshold).
3. Group qualifying candidates by `traderId | symbol`.

**Score formula:**

```
score = min(95,  60
           + avgSizeMultiplier × 4
           + spoofOrderCount × 5
           + max(0, 10 - avgCancelMs / 200))
```

The size multiplier is `quantity / avgExecutionQuantity`. Synthetic spoof orders have quantity 50,000–100,000 vs baseline ~1,000, giving multiplier ~50–100 → score always near 95.

**Severity:** `HIGH` if score ≥ 80, otherwise `MEDIUM`.

---

### 3.3 Wash Trading Detector

**What it looks for:** A trader repeatedly buying and selling the same symbol with matched quantities in rapid alternating cycles — artificially inflating volume without real economic exposure.

**Algorithm:**
1. Collect all `EXECUTE` events per `traderId | symbol`, sorted by time.
2. Scan adjacent pairs for:
   - Opposite sides (`BUY` then `SELL` or vice versa)
   - Window ≤ 60 seconds
   - Size delta ≤ 15% (`|qty1 - qty2| / max(qty1, qty2)`)
3. **Trigger condition:** `cycles ≥ 3`

**Score formula:**

```
score = min(98,  60
           + buySellCycles × 8
           + min(totalExecutedQuantity / 5000, 10))
```

Five cycles with 7,500 average quantity → score = 60 + 40 + 7.5 ≈ 98 (capped).

**Severity:** `HIGH` if score ≥ 85, otherwise `MEDIUM`.

---

### 3.4 Front Running Detector

**What it looks for:** A trader executes on the same side and symbol as a large institutional order, within 500 ms *before* that institutional order arrives — suggesting advance knowledge of order flow.

**Algorithm:**
1. Find the median quantity across all executions; set `institutionalThreshold = median × 5`.
2. For each large institutional execution (qty ≥ threshold):
   - Look for any *different* trader who executed the same side and symbol within `[0, 500] ms` before.
3. One alert per `traderId | symbol` pair (deduplicated).

**Score formula:**

```
score = min(90,  65 + int(institutionalQty / institutionalThreshold × 10))
```

The cap of 90 means front running never independently triggers CASE_NOTIFY_WATCHLIST — it lands in REVIEW (no formal case) under current thresholds.

**Severity:** `HIGH` if score ≥ 80, otherwise `MEDIUM`.

---

### 3.5 Momentum Ignition / Price Spike Detector

**What it looks for:** Rapid same-side executions that move the price by at least 1% within 90 seconds, using sufficient volume — designed to ignite momentum and attract follow-on liquidity before the manipulator reverses.

**Algorithm:**
1. Collect `EXECUTE` events per `traderId | symbol`, sorted by time.
2. For each starting event, build a 90-second window of same-side executions.
3. **Trigger conditions** (all must be true):
   - `sameSideCount ≥ 5`
   - `priceMovePct ≥ 1.0%`
   - `totalQuantity ≥ 30,000`
4. One alert per trader-symbol pair (break after first qualifying window).

**Score formula:**

```
score = min(97,  62
           + priceMovePct × 8
           + windowSize × 3
           + min(totalQty / 10000, 8))
```

The synthetic generator uses 1.5% price steps for 8 buys → total price move ~10.5% → score ≈ 62 + 84 + 24 + 8 = 178 → capped at 97.

**Severity:** `HIGH` if score ≥ 85, otherwise `MEDIUM`.

---

### Alert Object Shape

Every detector emits one or more `Alert` records:

```java
record Alert(
    String alertId,       // A-0001, assigned by PipelineService
    String pattern,       // "Layering / Spoofing", "Wash Trading", etc.
    String traderId,      // T-001
    String accountId,     // ACC-T-001
    String symbol,        // RELIANCE
    String severity,      // HIGH | MEDIUM
    int    score,         // 0–99 raw detection confidence
    Map<String,Object> metrics,     // pattern-specific metrics
    List<OrderEvent>   evidence,    // orders that triggered the alert
    Instant createdAt
)
```

Alerts are immediately persisted to `surveillance_alerts` and an `ALERT_CREATED` audit event is written.

---

## 4. Step 3 — Baseline Analysis

Before detection runs, `BaselineService.analyze(events)` computes statistics from the current batch:

```
SurveillanceBaseline
 ├── sampleCancelRatio          ← batch-wide cancel rate
 └── statsFor(traderId, symbol)
       ├── cancelRatio           ← trader's cancel rate for this symbol
       ├── averageExecutionQuantity
       ├── executedQuantity      ← total executed in this batch
       └── maxPriceMovePct       ← largest single-step price move
```

These baselines are used by detectors to normalize thresholds. For example, the Spoofing detector uses `averageExecutionQuantity` to set the size-multiplier threshold. The Layering detector reports `cancelRatioLift = traderCancelRatio / sampleCancelRatio` as a metric.

**Limitation:** Baselines are computed from the current batch only, not a 30-day historical warehouse. In production this would be replaced with a windowed aggregate query.

---

## 5. Step 4 — AI Triage

`TriageService.triage(alert)` is called once per alert.

### Decision tree

```
Is Claude configured (anthropic.enabled=true AND API key present)?
  ├── No  → offlineTriage(alert)
  └── Yes →
        Is this HIGH/CRITICAL? (or high-severity-only=false)
          ├── No  → offlineTriage(alert)
          └── Yes →
                Rate limiter has a slot? (5 req/sec)
                  ├── No  → offlineTriage(alert)  [logged as warning]
                  └── Yes → claudeTriage(alert)
```

### Claude Triage (`claudeTriage`)

Sends a structured JSON prompt to `POST https://api.anthropic.com/v1/messages`:

```json
{
  "model": "claude-sonnet-4-20250514",
  "max_tokens": 600,
  "temperature": 0.1,
  "messages": [{
    "role": "user",
    "content": "Return only valid JSON. Analyze as a financial markets surveillance analyst: { ...alert + rubric... }"
  }]
}
```

The rubric instructs Claude:
- Treat rule triggers as *candidate* alerts, not proof of misconduct.
- Escalate only when evidence is coherent, severe, and hard to explain as benign.
- Verdict must be exactly one of: `ESCALATE`, `REVIEW`, `IGNORE`.

Expected JSON response:

```json
{
  "verdict": "ESCALATE",
  "confidence": 91,
  "falsePositiveProbability": 8,
  "reason": "Short analyst reason.",
  "riskFactors": ["factor 1", "factor 2"],
  "falsePositiveFactors": ["counter-factor"],
  "recommendedActions": ["action 1"],
  "caseNote": "Analyst-ready paragraph for the compliance file."
}
```

### Offline Triage (`offlineTriage`)

Deterministic, no network required. Uses alert score thresholds:

```
score ≥ 75  → verdict = "ESCALATE"
score < 75  → verdict = "REVIEW"
confidence  = min(96, max(65, alert.score))
falsePositiveProbability = max(3, min(60, 100 - alert.score))
```

Pattern-specific `reason`, `riskFactors`, `falsePositiveFactors`, and `recommendedActions` are hard-coded for each of the five patterns.

### TriageResult shape

```java
record TriageResult(
    String verdict,                    // ESCALATE | REVIEW | IGNORE
    int    confidence,                 // 0–100 (AI certainty)
    int    falsePositiveProbability,   // 0–100
    String reason,
    List<String> riskFactors,
    List<String> falsePositiveFactors,
    List<String> recommendedActions,
    String caseNote,
    String source                      // "claude:model-id" | "offline-rule-triage"
)
```

Persisted to `triage_results`. Audit event `TRIAGE_COMPLETE` written.

---

## 6. Step 5 — Risk Scoring

`RiskScoreEngine.compute(alert, triage)` produces a 0–100 composite integer:

```
Risk Score  =  detectionScore     (30%)
            +  baselineDeviation  (25%)
            +  claudeScore        (25%)
            +  historicalScore    (20%)
```

### Component breakdown

| Component | Formula | Max contribution |
|---|---|---|
| Detection score | `alert.score × 0.30` | 30 |
| Baseline deviation | `min(100, │alert.score − 50│ × 2) × 0.25` | 25 |
| Claude confidence | `triage.confidence × 0.25` | 25 |
| Historical trader risk | `historicalRisk × 0.20` | 20 |

**Historical risk lookup:**
- If the trader is already in `watchlist_entries`: `min(100, entry.riskScore + 20)`
- If not on watchlist (first-time): **20** (default)
- Once watchlisted, subsequent alerts receive a permanent 20-point bonus, escalating future scoring

### Example calculations

| Scenario | Detection Score | Confidence | Historical | Risk Score | Decision |
|---|---|---|---|---|---|
| Layering (strong) | 99 | 96 | 20 (first) | 29.7+24.5+24+4=**82** | CASE (override→WATCHLIST) |
| Wash Trading | 98 | 96 | 20 | 29.4+24+24+4=**81** | CASE (override→NOTIFY) |
| Momentum Ignition | 97 | 96 | 20 | 29.1+23.5+24+4=**80** | CASE |
| Spoofing | 95 | 95 | 20 | 28.5+22.5+23.75+4=**79** | CASE |
| Front Running | 90 | 90 | 20 | 27+20+22.5+4=**74** | REVIEW (no case) |
| Layering (same trader, watchlisted, risk=80) | 99 | 96 | min(100,80+20)=100 → 20 | 29.7+24.5+24+**20**=**98** | CASE_NOTIFY_WATCHLIST |

The historical bonus is how the system creates a self-reinforcing escalation: a trader caught once gets watchlisted, and their *next* detection automatically hits a higher severity tier.

---

## 7. Step 6 — Escalation Decision

`RiskScoreEngine.decide(riskScore)` maps the numeric score to one of five decisions:

```
0  – 42   →  IGNORE
43 – 78   →  REVIEW            (medium risk — analyst review, no formal case)
79 – 87   →  CASE              (P1 compliance case)
88 – 95   →  CASE_AND_NOTIFY   (P1 case + email + Slack)
96 – 100  →  CASE_NOTIFY_WATCHLIST  (P0 case + notifications + watchlist)
```

### Score overrides (applied before the switch)

Two hard overrides in `EscalationService.process()` bypass the numeric band for extreme patterns:

```java
if (alert.score() >= 99) decision = CASE_NOTIFY_WATCHLIST;
else if (alert.score() >= 98 && decision < CASE_AND_NOTIFY) decision = CASE_AND_NOTIFY;
```

**Why overrides exist:** With a first-time trader (historicalRisk=20), even a perfect detection score of 99 produces a risk score of only ~82 — which sits in the CASE band, not CASE_NOTIFY_WATCHLIST. The override ensures that the most extreme patterns (Layering score=99, Wash Trading score=98) always trigger full escalation and create the initial watchlist entry, solving the bootstrapping problem.

---

## 8. Step 7 — Compliance Cases

`PersistenceService.createCase(alertId, priority, assignedTo)` writes to `compliance_cases`.

### When cases are created

| Decision | Priority | Assigned To |
|---|---|---|
| CASE | P1 | Surveillance Desk L2 |
| CASE_AND_NOTIFY | P1 | Surveillance Desk L2 |
| CASE_NOTIFY_WATCHLIST | P0 | Compliance Head |

**REVIEW does NOT create a case.** It only writes an `ALERT_REVIEWED` audit event. This is intentional — most medium-risk alerts should be reviewed by an analyst without consuming formal case management capacity.

### Case schema (`compliance_cases` table)

```sql
case_id      TEXT  PRIMARY KEY    -- CASE-XXXXXXXX
alert_id     TEXT  NOT NULL       -- FK → surveillance_alerts.alert_id
status       TEXT  DEFAULT 'OPEN' -- OPEN | CLOSED
priority     TEXT                 -- P0 | P1 | P2
assigned_to  TEXT
created_at   TIMESTAMPTZ
updated_at   TIMESTAMPTZ
```

Duplicate prevention: if a case already exists for an `alertId`, `createCase()` returns the existing case without inserting a new one.

Audit event `CASE_CREATED` is written immediately after the insert.

---

## 9. Step 8 — Notifications

`NotificationService.notify(alert, triage, riskScore)` sends to both channels in sequence:

### Email

- Recipient: `notifications.email.to` property (default: `compliance@tradesentinel.local`)
- Subject: `[Trade Sentinel] {severity} Alert — {pattern} | {traderId} | Risk Score {score}`
- Body: structured text with all alert and triage fields
- Transport: Spring `JavaMailSender` (SMTP)
- **If `MAIL_USERNAME` is blank:** status = `MOCK`, body logged at INFO, no send attempt
- **If send throws:** status = `FAILED`, exception logged, no re-throw

### Slack

- Target: `notifications.slack.webhook-url` property
- Payload: JSON with `text` (emoji + subject) and `attachments` (body, color by severity)
- Color: `#ef4444` (CRITICAL), `#f59e0b` (HIGH), `#3b82f6` (default)
- **If webhook URL is blank or contains "YOUR":** status = `MOCK`
- **If HTTP response ≠ 200:** status = `FAILED`

Both channels write a row to `notifications` regardless of outcome (`SENT`, `FAILED`, or `MOCK`). The audit event `NOTIFICATION_SENT` is written by `EscalationService` after the call — it records intent, not delivery confirmation.

---

## 10. Step 9 — Watchlist

`PersistenceService.addToWatchlist(traderId, riskScore, reason)` writes to `watchlist_entries`.

### When traders are watchlisted

Only `CASE_NOTIFY_WATCHLIST` triggers watchlisting. This happens when:
- Risk score is 96+ naturally (trader already on watchlist with high historical risk), OR
- Detection score is 99+ (score override in `EscalationService`)

### Upsert logic

If the trader already has a watchlist entry, the row is updated (higher risk score, incremented alert count). If not, a new row is inserted.

```sql
trader_id     TEXT  UNIQUE
risk_score    INT
alert_count   INT
status        TEXT  DEFAULT 'ACTIVE'
added_reason  TEXT
added_at      TIMESTAMPTZ
updated_at    TIMESTAMPTZ
```

### Effect on future scoring

Once a trader is on the watchlist, `RiskScoreEngine.compute()` reads their current `riskScore` from the table:

```java
int historicalRisk = watchlistRepository.findByTraderId(traderId)
    .map(e -> Math.min(100, e.getRiskScore() + 20))
    .orElse(20);
```

A watchlisted trader with `riskScore=80` contributes `min(100, 100) × 0.20 = 20` to every future alert's risk score — versus the default contribution of `20 × 0.20 = 4`. This +16 point boost means a borderline alert for a watchlisted trader will escalate one level higher than for a clean trader.

Audit event `WATCHLIST_AUTO_ADD` is written.

---

## 11. Step 10 — Audit Trail

Every significant state transition writes a row to `audit_events`. The audit trail is append-only — no updates or deletes.

### Audit events per alert lifecycle

```
ALERT_CREATED           ← detection found the pattern (always)
TRIAGE_COMPLETE         ← triage verdict assigned (always)
RISK_SCORED             ← risk score computed (always)

Then exactly one of:
  ALERT_IGNORED         ← risk ≤ 42, no action
  ALERT_REVIEWED        ← risk 43–78, analyst review only (no case)
  CASE_CREATED          ← risk 79+ or score override, compliance case filed
  NOTIFICATION_SENT     ← CASE_AND_NOTIFY or CASE_NOTIFY_WATCHLIST
  WATCHLIST_AUTO_ADD    ← CASE_NOTIFY_WATCHLIST only
```

### Querying the audit trail

The dashboard calls `GET /api/audit` which executes a JPQL query directly against PostgreSQL:

```sql
SELECT a FROM AuditEventEntity a
WHERE (:eventType = '' OR a.eventType LIKE CONCAT('%', :eventType, '%'))
  AND (:search = ''
       OR LOWER(COALESCE(a.description,'')) LIKE LOWER(CONCAT('%', :search, '%'))
       OR LOWER(COALESCE(a.alertId,''))     LIKE LOWER(CONCAT('%', :search, '%'))
       OR LOWER(COALESCE(a.actor,''))       LIKE LOWER(CONCAT('%', :search, '%')))
ORDER BY a.eventTime DESC
```

This queries the entire database, not just the currently visible page. Pagination is handled server-side via Spring Data's `Pageable`.

---

## 12. Entity Relationships

```
order_events
   │  run_id (groups batch)
   │
   └──► surveillance_alerts  (alertId FK)
              │
              ├──► triage_results      (alertId, 1:1)
              │
              ├──► compliance_cases    (alertId, 1:0..1)
              │        │
              │        └── (case escalation creates notifications)
              │
              ├──► notifications       (alertId, 1:many — one EMAIL + one SLACK)
              │
              └──► audit_events        (alertId, 1:many)
                        └── ALERT_CREATED
                            TRIAGE_COMPLETE
                            RISK_SCORED
                            ALERT_IGNORED | ALERT_REVIEWED | CASE_CREATED
                            NOTIFICATION_SENT (conditional)
                            WATCHLIST_AUTO_ADD (conditional)

watchlist_entries  (traderId UNIQUE)
   └── riskScore affects future scoring via RiskScoreEngine
```

### Key constraints

- `surveillance_alerts.alertId` is unique. `saveAlert()` is idempotent — a duplicate `alertId` is silently skipped.
- `compliance_cases.alertId` has at most one case per alert. `createCase()` returns existing if present.
- `watchlist_entries.traderId` is unique. `addToWatchlist()` upserts.
- `audit_events` has no unique constraint — multiple events per alert are expected and correct.

---

## 13. Score Math Reference

### Detection score ranges by pattern

| Pattern | Minimum | Maximum | Typical (synthetic) |
|---|---|---|---|
| Layering | ~82 | 99 | 99 |
| Wash Trading | ~84 | 98 | 98 |
| Momentum Ignition | ~88 | 97 | 97 |
| Pump & Dump | ~88 | 97 | 97 |
| Spoofing | ~85 | 95 | 95 |
| Front Running | 65 | 90 | 90 |

### Risk score at first-time detection (historicalRisk = 20, contribution = 4)

| Detection Score | Baseline Dev | Confidence | Risk Score | Decision |
|---|---|---|---|---|
| 99 | 24.5 | 24 (conf=96) | **82** | CASE (override to WATCHLIST) |
| 98 | 24.0 | 24 (conf=96) | **81** | CASE (override to NOTIFY) |
| 97 | 23.5 | 24 (conf=96) | **80** | CASE |
| 95 | 22.5 | 23.75 | **79** | CASE |
| 90 | 20.0 | 22.5 | **74** | REVIEW |
| 85 | 17.5 | 21.25 | **66** | REVIEW |

### Risk score for watchlisted trader (historicalRisk = 100, contribution = 20)

| Detection Score | Risk Score | Decision |
|---|---|---|
| 99 | 98 | CASE_NOTIFY_WATCHLIST |
| 95 | 94 | CASE_AND_NOTIFY |
| 90 | 90 | CASE_AND_NOTIFY |
| 85 | 82 | CASE |

---

## 14. Event Type Reference

| Event Type | Written By | Meaning |
|---|---|---|
| `ALERT_CREATED` | `PersistenceService.saveAlert()` | Pattern detector fired |
| `TRIAGE_COMPLETE` | `PersistenceService.saveTriage()` | AI/offline triage verdict assigned |
| `RISK_SCORED` | `EscalationService.process()` | Composite risk score computed |
| `ALERT_IGNORED` | `EscalationService.process()` | Risk ≤ 42, false positive, no action |
| `ALERT_REVIEWED` | `EscalationService.process()` | Risk 43–78, queued for analyst review |
| `CASE_CREATED` | `PersistenceService.createCase()` | Formal compliance case filed |
| `NOTIFICATION_SENT` | `EscalationService.process()` | Email + Slack dispatch attempted |
| `WATCHLIST_AUTO_ADD` | `EscalationService.process()` | Trader added to high-risk watchlist |
| `WATCHLIST_UPDATED` | `PersistenceService.addToWatchlist()` | Existing watchlist entry updated |

---

## 15. Code Map

```
src/main/java/com/example/tradesentinel/
│
├── service/
│   ├── ScenarioScheduler.java        ← @Scheduled tick, live feed, manual triggers
│   ├── SyntheticTradeGenerator.java  ← 6 scenario generators, pickScenario() probabilities
│   ├── PipelineService.java          ← orchestrates detection → triage → escalation
│   ├── BaselineService.java          ← per-batch statistical baseline
│   ├── DetectionService.java         ← 5 pattern detectors
│   ├── TriageService.java            ← Claude API + offline fallback, rate limiter
│   ├── RiskScoreEngine.java          ← composite scoring formula + decision matrix
│   ├── EscalationService.java        ← decision dispatch, score overrides
│   ├── PersistenceService.java       ← all DB writes (alerts, cases, watchlist, audit)
│   ├── NotificationService.java      ← email (JavaMailSender) + Slack (HTTP webhook)
│   ├── CsvOrderParser.java           ← CSV ingestion
│   └── JsonOrderParser.java          ← JSON ingestion
│
├── web/
│   ├── DashboardController.java      ← page routes + /api/audit + /api/events/recent
│   └── TradeSentinelController.java  ← /api/replay, /api/trigger/{scenario}, /api/trades/live
│
├── entity/          ← JPA entities (7 tables)
│   ├── OrderEventEntity.java
│   ├── SurveillanceAlertEntity.java
│   ├── TriageResultEntity.java
│   ├── ComplianceCaseEntity.java
│   ├── NotificationEntity.java
│   ├── WatchlistEntryEntity.java
│   └── AuditEventEntity.java
│
├── repository/      ← Spring Data JPA repos (one per entity)
│   └── AuditEventRepository.java     ← findFiltered() JPQL query for audit API
│
└── model/           ← Immutable records (in-memory pipeline objects)
    ├── OrderEvent.java
    ├── Alert.java
    ├── TriageResult.java
    ├── PipelineResult.java
    └── ...

src/main/resources/
├── application.properties            ← all config with ${ENV_VAR:default} overrides
├── db/migration/V1__init.sql         ← Flyway schema (7 tables + indexes)
└── templates/dashboard.html          ← Thymeleaf + vanilla JS dashboard
```

---

## Appendix — Why REVIEW Does Not Create a Case

A common question about the design: if REVIEW means "suspicious enough to flag", why not create a compliance case?

In real compliance operations there is a strong distinction:
- **Quick review** — an analyst checks the alert in a queue, confirms it is a false positive or minor anomaly, and closes it in under a minute. No formal record is needed.
- **Compliance case** — a documented investigation with priority, SLA, assignee, audit log, and possible regulatory reporting obligation.

Creating a formal case for every borderline alert floods the case management system, burns analyst time on low-value work, and dilutes the signal-to-noise ratio for genuinely serious issues.

The REVIEW decision records `ALERT_REVIEWED` in the audit trail so there is always a complete record of what was seen and when, without the overhead of a formal case. Only risk scores above 79, or pattern-specific overrides for extreme scores, justify the compliance overhead of a formal case.
