# Trade Sentinel

**AI-Powered Trade Surveillance & Compliance Automation Platform**

A production-grade Spring Boot application that monitors live order flow, detects market manipulation patterns in real time, triages alerts with Claude AI, and automates compliance workflows — cases, notifications, watchlist management, and immutable audit trails.

Built for the Wissen Technology Hackathon 2026.

---

## What It Does

Every trade event passes through five detection algorithms. When suspicious patterns are found, Claude Sonnet (or a deterministic offline rule engine) triages the alert, assigns a composite risk score, and the system automatically decides whether to file a compliance case, notify via email and Slack, and add the trader to a watchlist — all written to an immutable audit trail in PostgreSQL.

---

## System Architecture

```
 ┌──────────────────────────────────────────────────────────────────────────┐
 │                        TRADE SENTINEL — Spring Boot 3.3.5                │
 │                                                                          │
 │  ┌─────────────────────────────────────────────────────────────────────┐ │
 │  │  INGESTION LAYER                                                    │ │
 │  │                                                                     │ │
 │  │  ScenarioScheduler           CSV / JSON Upload                      │ │
 │  │  (every 3 seconds)     OR    POST /api/replay                       │ │
 │  │       │                             │                               │ │
 │  │       └──────────────┬──────────────┘                               │ │
 │  │                      ▼                                              │ │
 │  │             List<OrderEvent>                                        │ │
 │  │         saved → order_events table                                  │ │
 │  └──────────────────────┬────────────────────────────────────────────-─┘ │
 │                         │                                                │
 │  ┌──────────────────────▼────────────────────────────────────────────-─┐ │
 │  │  DETECTION LAYER  (DetectionService)                                │ │
 │  │                                                                     │ │
 │  │  BaselineService.analyze()  ◄── computes cancel ratios, qty, price  │ │
 │  │                                                                     │ │
 │  │  ┌───────────────┐  ┌──────────────┐  ┌─────────────────────────┐  │ │
 │  │  │   LAYERING    │  │   SPOOFING   │  │      WASH TRADING       │  │ │
 │  │  │  score 82–99  │  │  score 75–95 │  │      score 84–98        │  │ │
 │  │  └───────────────┘  └──────────────┘  └─────────────────────────┘  │ │
 │  │  ┌──────────────────────┐  ┌────────────────────────────────────┐  │ │
 │  │  │    FRONT RUNNING     │  │   MOMENTUM IGNITION / PUMP & DUMP  │  │ │
 │  │  │    score 65–90       │  │         score 88–97                │  │ │
 │  │  └──────────────────────┘  └────────────────────────────────────┘  │ │
 │  │                                                                     │ │
 │  │  Each detector emits Alert(alertId, pattern, score, evidence…)      │ │
 │  │  alertId = A-{UUID8}  ← unique per tick, never reused              │ │
 │  └──────────────────────┬────────────────────────────────────────────-─┘ │
 │                         │                                                │
 │  ┌──────────────────────▼────────────────────────────────────────────-─┐ │
 │  │  TRIAGE LAYER  (TriageService)                                      │ │
 │  │                                                                     │ │
 │  │  HIGH severity alert?                                               │ │
 │  │       ├── YES + API key present → Claude Sonnet API                 │ │
 │  │       │        returns: verdict · confidence · FP probability       │ │
 │  │       │                 risk factors · case note                    │ │
 │  │       └── NO  / fallback      → Offline Rule Engine                 │ │
 │  │                                 deterministic, same output shape    │ │
 │  │                                                                     │ │
 │  │  Verdict:  ESCALATE  │  REVIEW  │  IGNORE                          │ │
 │  └──────────────────────┬────────────────────────────────────────────-─┘ │
 │                         │                                                │
 │  ┌──────────────────────▼────────────────────────────────────────────-─┐ │
 │  │  RISK SCORING  (RiskScoreEngine)                                    │ │
 │  │                                                                     │ │
 │  │  Score = detection(30%) + baseline_deviation(25%)                   │ │
 │  │        + ai_confidence(25%)  + historical_trader_risk(20%)          │ │
 │  │                                                                     │ │
 │  │  First-time trader → historical = 20                                │ │
 │  │  Watchlisted trader → historical = min(100, entry.risk + 20)        │ │
 │  └──────────────────────┬────────────────────────────────────────────-─┘ │
 │                         │                                                │
 │  ┌──────────────────────▼────────────────────────────────────────────-─┐ │
 │  │  ESCALATION  (EscalationService)                                    │ │
 │  │                                                                     │ │
 │  │  Risk 0–42   → IGNORE          audit: ALERT_IGNORED                 │ │
 │  │  Risk 43–69  → REVIEW          audit: ALERT_REVIEWED  (no case)     │ │
 │  │  Risk 70–87  → CASE            P1 → Surveillance Desk L2            │ │
 │  │  Risk 88–95  → CASE+NOTIFY     P1 + Email + Slack                   │ │
 │  │  Risk 96–100 → CASE+NOTIFY     P0 + Email + Slack + Watchlist       │ │
 │  │              + WATCHLIST                                             │ │
 │  │                                                                     │ │
 │  │  Score overrides (bypass numeric band):                             │ │
 │  │    score ≥ 99  → always CASE_NOTIFY_WATCHLIST  (Layering)           │ │
 │  │    score = 98  → always CASE_AND_NOTIFY         (Wash Trading)      │ │
 │  └──────────────────────┬────────────────────────────────────────────-─┘ │
 │                         │                                                │
 │           ┌─────────────┼──────────────────┐                            │
 │           ▼             ▼                  ▼                            │
 │    compliance_cases  notifications    watchlist_entries                  │
 │    audit_events      (Email+Slack)    (trader risk history)              │
 │                                                                          │
 │  DashboardController → Thymeleaf + vanilla JS (dark / light theme)      │
 │  /api/audit · /api/alerts/recent · /api/cases/recent · /api/charts/data │
 └──────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
              PostgreSQL 17  (7 tables, Flyway migrations)
              order_events · surveillance_alerts · triage_results
              compliance_cases · notifications · watchlist_entries · audit_events
```

---

## Alert Flow — How It Works End to End

This is the full journey of a single suspicious trade event, from raw order data to a compliance case on the dashboard. Walk through this during the demo.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 1 — ORDER EVENTS ARRIVE                                               │
│                                                                             │
│  Every 3 seconds the scheduler generates a batch of synthetic OrderEvents.  │
│  Each event has: traderId, symbol, side (BUY/SELL), quantity, price,        │
│  eventType (NEW / EXECUTE / CANCEL), and a timestamp.                       │
│                                                                             │
│  Example batch (Layering scenario):                                         │
│    T-003 BUY  RELIANCE 45,000 @ 2,450  NEW     09:15:00.000                │
│    T-003 BUY  RELIANCE 45,000 @ 2,449  NEW     09:15:00.050                │
│    T-003 BUY  RELIANCE 45,000 @ 2,448  NEW     09:15:00.100   ← 8 large    │
│    ...                                           CANCEL × 8   ← cancelled  │
│    T-003 SELL RELIANCE  3,500 @ 2,451  EXECUTE 09:15:00.300   ← real trade │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 2 — BASELINE ANALYSIS                                                 │
│                                                                             │
│  Before detection, BaselineService scans the batch to establish norms:      │
│    • sampleCancelRatio  — what % of orders are normally cancelled           │
│    • averageExecutionQuantity — typical order size for this trader/symbol   │
│    • maxPriceMovePct    — largest normal single-step price change           │
│                                                                             │
│  Detectors compare the current batch against these baselines.               │
│  A cancel ratio of 95% is only suspicious if the baseline is 15%.          │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 3 — FIVE DETECTORS RUN IN PARALLEL                                    │
│                                                                             │
│  Each detector scans the same batch independently:                          │
│                                                                             │
│  Layering    → 8 large cancels, cancelRatio=1.0, cancelTime=400ms → ALERT  │
│  Spoofing    → order size 50× baseline, cancelled in 250ms       → ALERT   │
│  Wash Trade  → 5 buy/sell cycles, qty matched ±5%, 20s apart     → ALERT   │
│  Front Run   → executed 180ms before 300,000-unit institutional   → ALERT   │
│  Momentum    → 8 same-side buys moved price 10.5% in 80 seconds  → ALERT   │
│                                                                             │
│  Each alert gets a unique ID:  A-3F9B2E1A  (UUID, never reused)            │
│  Alert is saved to surveillance_alerts table.                               │
│  Audit event ALERT_CREATED is written.                                      │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 4 — CLAUDE AI TRIAGE  (or offline rule fallback)                      │
│                                                                             │
│  For each HIGH-severity alert, the system calls Claude Sonnet with a        │
│  structured JSON prompt containing all alert metrics and evidence.          │
│                                                                             │
│  Claude is instructed to act as a compliance analyst and return:            │
│    verdict            →  ESCALATE / REVIEW / IGNORE                        │
│    confidence         →  0–100 (how certain the AI is)                     │
│    falsePositiveProb  →  0–100 (chance this is benign)                     │
│    riskFactors        →  ["High cancel ratio vs baseline", ...]             │
│    caseNote           →  "Analyst-ready paragraph for the compliance file"  │
│    recommendedActions →  ["Open L2 surveillance case", ...]                │
│                                                                             │
│  If Claude is unavailable → offline rule engine fires:                      │
│    score ≥ 75 → ESCALATE   |   score < 75 → REVIEW                        │
│    confidence = min(96, max(65, detectionScore))                            │
│                                                                             │
│  Triage result saved to triage_results table.                               │
│  Audit event TRIAGE_COMPLETE is written.                                    │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 5 — COMPOSITE RISK SCORE  (0–100)                                     │
│                                                                             │
│  RiskScoreEngine combines four weighted components:                         │
│                                                                             │
│    Detection Score      ×  0.30   (how strong the pattern signal is)       │
│    Baseline Deviation   ×  0.25   (how far above normal behaviour)         │
│    AI Confidence        ×  0.25   (Claude's certainty, or rule proxy)      │
│    Historical Risk      ×  0.20   (is this trader already watchlisted?)    │
│                                                                             │
│  Example — Layering alert (score 99, first-time trader):                    │
│    99 × 0.30  =  29.7                                                       │
│    90 × 0.25  =  22.5   (deviation from 50-midpoint)                       │
│    96 × 0.25  =  24.0   (Claude confidence)                                │
│    20 × 0.20  =   4.0   (default for unknown trader)                       │
│    ─────────────────────                                                    │
│    RISK SCORE   =  80                                                       │
│                                                                             │
│  Same trader, second offence (now watchlisted, stored risk = 80):           │
│    historical = min(100, 80+20) = 100  →  100 × 0.20 = 20                 │
│    RISK SCORE   =  96  ← escalates one full tier higher automatically      │
│                                                                             │
│  Audit event RISK_SCORED is written.                                        │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 6 — ESCALATION DECISION                                               │
│                                                                             │
│  Risk Score  →  Decision             →  What happens                       │
│  ─────────────────────────────────────────────────────────────────────────  │
│   0 – 42    →  IGNORE               →  audit: ALERT_IGNORED only           │
│  43 – 69    →  REVIEW               →  audit: ALERT_REVIEWED  (no case)    │
│  70 – 87    →  CASE                 →  P1 case → Surveillance Desk L2      │
│  88 – 95    →  CASE + NOTIFY        →  P1 case + Email + Slack             │
│  96 – 100   →  CASE + NOTIFY        →  P0 case + Email + Slack             │
│             +  WATCHLIST            →  trader added to watchlist            │
│                                                                             │
│  Score overrides (hardcoded, bypass numeric band):                         │
│    detectionScore ≥ 99  →  always CASE_NOTIFY_WATCHLIST  (Layering)        │
│    detectionScore = 98  →  always CASE_AND_NOTIFY         (Wash Trading)   │
│                                                                             │
│  Compliance case written to compliance_cases table.                         │
│  Notification written to notifications table (SENT or MOCK).               │
│  Watchlist entry written to watchlist_entries table.                        │
│  All decisions written to audit_events (immutable).                         │
└────────────────────────────────┬────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 7 — DASHBOARD & TRACEABILITY                                          │
│                                                                             │
│  Every screen links back to the originating Alert ID (A-3F9B2E1A):         │
│                                                                             │
│  Alerts page   → card shows alertId, pattern, Claude verdict, case note    │
│  Cases page    → "Source Alert ID" column links back to the alert card     │
│  Watchlist     → "Source Alert & Reason" shows alertId + risk score        │
│  Audit Trail   → every row shows alertId as a clickable link               │
│                                                                             │
│  A judge can follow the full chain:                                         │
│    A-3F9B2E1A (Alert) → Triage → CASE-AB12 → Slack sent → T-003 watchlisted│
│                                                                             │
│  Toast notifications appear automatically in the bottom-right corner       │
│  whenever a new alert, case, notification, or watchlist event fires.        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## How Each Alert Type Is Flagged

### 1 — Layering / Spoofing (score 82–99)

**What it is:** Placing large visible orders to move the order book, then cancelling them before execution to benefit real trades on the opposite side.

**Detection logic:**
```
FOR each trader-symbol pair in the batch:
  collect all CANCEL events that have a matching NEW
  filter to orders with quantity ≥ 25,000
  TRIGGER if:
    largeCancelledOrders ≥ 6
    AND cancelRatio ≥ 0.70          ← 70%+ of large orders cancelled
    AND medianCancelTimeMs ≤ 1,500  ← cancelled within 1.5 seconds

Score = min(99,  55
           + cancelRatio × 25
           + largeCancelledOrders × 2
           + oppositeSideExecutions × 3)
```

**Key signals:** High cancel-ratio, rapid cancellation, opposite-side fills during window.
**Escalation:** Score 99 → always P0 case + Slack + watchlist (score override).

---

### 2 — Spoofing (score 75–95)

**What it is:** Individual large orders placed and pulled before any fill, within milliseconds — designed to create a false impression of supply or demand.

**Detection logic:**
```
FOR each cancelled order never executed (cancelMs ∈ [0, 2000]):
  TRIGGER if quantity ≥ 5 × trader's average execution quantity

Score = min(95,  60
           + avgSizeMultiplier × 4    ← how many times larger than normal
           + spoofOrderCount × 5
           + max(0, 10 − avgCancelMs / 200))
```

**Key signals:** Order size is 5× to 100× normal; cancelled before any fill; sub-second lifecycle.

---

### 3 — Wash Trading (score 84–98)

**What it is:** A trader simultaneously buying and selling the same instrument with matched quantities — inflating reported volume with no real economic change of ownership.

**Detection logic:**
```
FOR each consecutive EXECUTE pair (same trader, same symbol):
  TRIGGER cycle if:
    sides are opposite (BUY↔SELL)
    AND time gap ≤ 60 seconds
    AND size delta ≤ 15%             ← quantities nearly identical

ALERT if cycles ≥ 3

Score = min(98,  60
           + buySellCycles × 8
           + min(totalQty / 5000, 10))
```

**Key signals:** Repeated buy-sell cycles, matched quantities, same trader both sides.
**Escalation:** Score 98 → always P1 case + email + Slack (score override).

---

### 4 — Front Running (score 65–90)

**What it is:** Executing on the same side as an incoming large institutional order within 500 ms before it arrives — suggesting advance knowledge of order flow.

**Detection logic:**
```
Find institutional executions where quantity ≥ 5 × median batch quantity

FOR each institutional execution:
  look for a different trader who:
    same symbol AND same side
    executed within [0, 500 ms] BEFORE the institutional order

Score = min(90,  65 + int(institutionalQty / threshold × 10))
```

**Key signals:** Sub-500 ms lead time, same side and symbol as institutional flow.

---

### 5 — Momentum Ignition / Pump & Dump (score 88–97)

**What it is:** A burst of rapid same-side executions that drives the price up 1%+ within 90 seconds, designed to attract follow-on buyers before the manipulator reverses.

**Detection logic:**
```
FOR each 90-second window of same-side executions:
  TRIGGER if:
    sameSideCount ≥ 5
    AND priceMovePct ≥ 1.0%          ← price moved at least 1%
    AND totalQuantity ≥ 30,000

Score = min(97,  62
           + priceMovePct × 8
           + windowSize × 3
           + min(totalQty / 10000, 8))
```

**Key signals:** Rapid same-direction executions, measurable price impact, concentrated volume.

---

## Alert → Case → Watchlist Flow

```
New OrderEvent batch
        │
        ▼
  DetectionService ──── no pattern found ──► (nothing, normal trade logged)
        │
        │ pattern found
        ▼
  Alert created  (A-{UUID})
  saved → surveillance_alerts
  audit → ALERT_CREATED
        │
        ▼
  TriageService
  ├── Claude Sonnet  (if HIGH severity + API key)
  └── Rule Engine    (fallback)
        │
        │ verdict + confidence + case note
        ▼
  RiskScoreEngine.compute()
  ├── 30% detection score
  ├── 25% baseline deviation
  ├── 25% AI confidence
  └── 20% historical trader risk
        │
        ▼
  EscalationService.decide(riskScore)
        │
        ├── IGNORE (0–42)
        │     └─ audit: ALERT_IGNORED
        │
        ├── REVIEW (43–69)
        │     └─ audit: ALERT_REVIEWED
        │
        ├── CASE (70–87)
        │     └─ compliance_cases (P1, Surveillance L2)
        │        audit: CASE_CREATED
        │
        ├── CASE_AND_NOTIFY (88–95 or score=98)
        │     ├─ compliance_cases (P1)
        │     ├─ Email notification
        │     ├─ Slack notification
        │     └─ audit: NOTIFICATION_SENT
        │
        └── CASE_NOTIFY_WATCHLIST (96–100 or score≥99)
              ├─ compliance_cases (P0, Compliance Head)
              ├─ Email + Slack
              ├─ watchlist_entries (trader auto-added)
              └─ audit: WATCHLIST_AUTO_ADD
                        (future alerts for this trader
                         get +20 historical risk bonus)
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.3.5 |
| Database | PostgreSQL 17, Spring Data JPA, Flyway |
| AI | Anthropic Claude Sonnet (`claude-sonnet-4-20250514`) |
| HTTP | Spring Web MVC, Thymeleaf, vanilla JS |
| Build | Gradle 8 |
| Container | Docker (multi-stage, eclipse-temurin:21-jre-alpine) |
| Deployment | Render (Docker web service + managed PostgreSQL) |
| Observability | Spring Actuator, SLF4J + Logback |

---

## Quick Start (Local)

### Prerequisites
- Java 21+
- PostgreSQL 17

```sql
CREATE DATABASE tradesentinel;
CREATE USER tradesentinel WITH PASSWORD 'tradesentinel';
GRANT ALL PRIVILEGES ON DATABASE tradesentinel TO tradesentinel;
```

### Run

```powershell
cp .env.example .env   # fill in your values
.\gradlew.bat bootRun
```

Open `http://localhost:8080`. The scheduler starts immediately — alerts appear within the first minute.

### Build JAR

```powershell
.\gradlew.bat clean bootJar
java -jar build\libs\trade-sentinel-0.0.1-SNAPSHOT.jar
```

---

## Deploy to Render (Docker)

1. Push `Dockerfile` and `.dockerignore` to GitHub.
2. Render → **New → Web Service → Docker**.
3. Set environment variables in Render dashboard:

| Variable | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://<host>:5432/<db>` |
| `DB_USER` | Render Postgres user |
| `DB_PASSWORD` | Render Postgres password |
| `ANTHROPIC_ENABLED` | `true` |
| `ANTHROPIC_API_KEY` | `sk-ant-...` |
| `SLACK_WEBHOOK_URL` | `https://hooks.slack.com/...` |
| `NOTIFICATION_EMAIL` | recipient address |
| `MAIL_USERNAME` | *(leave blank on Render — SMTP blocked, uses MOCK)* |
| `SERVER_PORT` | `8080` |

> **DATABASE_URL format:** Render shows `postgresql://user:pass@host/db` — prefix it with `jdbc:` → `jdbc:postgresql://user:pass@host/db`.

Flyway runs migrations automatically on first boot. No manual DB setup needed.

---

## Dashboard Pages

| URL | What You See |
|---|---|
| `/` | Live trade feed, 4 charts, scenario trigger buttons, CSV upload |
| `/alerts` | Top 50 alerts with AI triage panel — auto-refreshes every 8 s |
| `/cases` | Compliance cases with source Alert ID links — auto-refreshes every 8 s |
| `/watchlist` | High-risk traders sorted by risk score, source alert in reason |
| `/audit` | Server-side filtered, searchable, paginated audit trail |

---

## REST API

| Endpoint | Description |
|---|---|
| `GET /api/health` | Runtime status, Claude configuration |
| `GET /api/sample` | Bundled sample CSV |
| `POST /api/replay` | Run detection pipeline on CSV |
| `GET /api/trades/live` | Live trade feed (last 200 events) |
| `POST /api/trigger/{scenario}` | Manual scenario trigger |
| `GET /api/alerts/recent` | Latest 50 alerts with triage (JSON) |
| `GET /api/cases/recent` | Latest 50 compliance cases (JSON) |
| `GET /api/audit` | Filtered, paginated audit trail |
| `GET /api/events/recent?since=<epoch>` | New events since timestamp (toast polling) |
| `GET /api/charts/data` | Chart datasets |

---

## Demo Scenarios (Auto + Manual)

| Scenario | Auto % | Score | Typical Outcome |
|---|---|---|---|
| Normal trade | 95% | — | No alert |
| Layering | 2% | 99 | P0 case + Slack + watchlist |
| Wash Trading | 1% | 98 | P1 case + Slack |
| Spoofing | 1% | 95 | P1 case |
| Momentum Ignition | 1% | 97 | P1 case |
| Front Running | manual | 90 | P1 case |
| Pump & Dump | manual | 97 | P1 case |

**3-minute demo expectation** (60 scheduler ticks): ~3 new alerts, ~2–3 compliance cases, ~1 notification event, ~0–1 watchlist add.

---

## Detailed Documentation

| Document | Contents |
|---|---|
| [`docs/ALERT_FLOW.md`](docs/ALERT_FLOW.md) | Complete alert lifecycle with score formulas and entity relationships |
| [`docs/API.md`](docs/API.md) | REST endpoint reference |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | System design and component map |

---

## Known Limits

- No real exchange feed; synthetic data only unless CSV is uploaded.
- Baselines are computed per batch, not from a historical 30-day warehouse.
- Claude triage requires network access and a valid API key; offline rule engine is always available as fallback.
- SMTP (email) is blocked on Render free tier — set `MAIL_USERNAME=` blank to use MOCK mode; Slack webhook works normally.
