# CLAUDE CODE EXECUTION PLAN

 can we in some way reducve just case scenario? its all filled with case scenario, i want all types. also when in audit page the new entry comes which is mostly "cases" only, and i try to filter others, it doesnt load, tho they were present, the filtering only wqorks for data visible on audit page, it should be queriying data from db right and i shuld be able to search all the adat from db like multiple pages..



Root cause: Thymeleaf evaluates each ${...} block independently and converts it to the string "true" or "false". The ? : operators sitting outside those blocks were just literal characters, not real ternary logic — so the expression chain never worked, and every row landed on audit-case (the third branch happened to match in the concatenated string output).

Fix: Compute the category once using th:with="cat=${...}" — a single ${} containing the full nested ternary in SpEL — then reference ${cat} for both th:class and the chip th:class. Also added a readable label variable so instead of raw TRIAGE_COMPLETE you'll see 🤖 Triage Complete, etc.

The 6 distinct event types now correctly categorized:

┌───────────────────────────────────────┬────────────────────┬────────────────────────────┐
│              Event type               │       Color        │       Filter button        │
├───────────────────────────────────────┼────────────────────┼────────────────────────────┤
│ ALERT_CREATED, ALERT_IGNORED          │ 🔴 Red left border │ Alerts                     │
├───────────────────────────────────────┼────────────────────┼────────────────────────────┤
│ TRIAGE_COMPLETE                       │ 🟣 Indigo          │ Triage                     │
├───────────────────────────────────────┼────────────────────┼────────────────────────────┤
│ CASE_CREATED                          │ 🔵 Blue            │ Cases                      │
├───────────────────────────────────────┼────────────────────┼────────────────────────────┤
│ WATCHLIST_AUTO_ADD, WATCHLIST_UPDATED │ 🟡 Amber           │ Watchlist                  │
├───────────────────────────────────────┼────────────────────┼────────────────────────────┤
│ NOTIFICATION_SENT                     │ 🟢 Green           │ Notifications (new button) │
├───────────────────────────────────────┼────────────────────┼────────────────────────────┤
│ RISK_SCORED                           │ ⚫ Grey            │ — (shows under All)        │
└───────────────────────────────────────┴────────────────────┴────────────────────────────┘


## Global Instructions

Read:

* @docs/IMPLEMENTATION_PLAN.md
* @docs/imlpl-2.md

Primary source of truth:

@docs/imlpl-2.md

Important:

* Implement only what is explicitly present in the implementation plan.
* Do not add Kafka.
* Do not add Redis.
* Do not add Microservices.
* Do not add XGBoost.
* Do not add Isolation Forest.
* Do not add ServiceNow.
* Do not add Jira integration.

Architecture must remain:

Spring Boot + PostgreSQL + Thymeleaf + Claude API

Use Java 21.

Use Gradle.

Use Flyway.

All code must compile.

After every phase:

* Update README
* Verify build passes
* Verify application starts

Stop after completing the requested phase.

---

# PHASE 1

Goal:

Project Foundation

Implement:

1. Gradle Configuration
2. Application Configuration
3. Flyway Setup
4. PostgreSQL Integration
5. Environment Variable Configuration
6. Domain Entities
7. JPA Repositories

Entities:

* OrderEvent
* SurveillanceAlert
* TriageResult
* ComplianceCase
* Notification
* WatchlistEntry
* AuditEvent

Requirements:

* Proper relationships
* JPA annotations
* Auditing support
* Repository layer

Also generate:

* Initial Flyway migrations
* application.properties
* .env.example
* .gitignore

Do NOT generate:

* Services
* Controllers
* Scheduler
* Claude Integration

When finished:

Run build validation and stop.

---

# PHASE 2

Goal:

Trade Generation + Detection Engine

Implement:

1. Scenario Scheduler
2. Synthetic Trade Generator
3. CSV Upload
4. Replay Engine

Scenarios:

* Normal Trading
* Layering
* Spoofing
* Wash Trading
* Front Running
* Momentum Ignition
* Pump & Dump

Distribution:

* Normal: 90%
* Layering: 3%
* Spoofing: 2%
* Wash Trading: 2%
* Front Running: 1%
* Momentum Ignition: 1%
* Pump & Dump: 1%

Implement Detection Engine:

* Layering Detector
* Spoofing Detector
* Wash Trading Detector
* Front Running Detector

Detection Output:

Create SurveillanceAlert records.

Do NOT generate:

* Claude integration
* Dashboard

When finished:

Verify alerts are generated automatically.

Stop.

---

# PHASE 3

Goal:

Claude Triage Engine

Implement:

1. Claude Client
2. Prompt Templates
3. Alert Review Service
4. Triage Result Storage

Rules:

Only HIGH and CRITICAL alerts go to Claude.

Never send:

* Trades
* Replay data
* Raw market data

Claude returns:

* Verdict
* Confidence
* False Positive Probability
* Reasoning
* Recommended Action

Implement:

anthropic.high-severity-only=true

anthropic.max-alerts-per-run=100

Persist results in database.

When finished:

Verify alert triage works.

Stop.

---

# PHASE 4

Goal:

Risk Score + Escalation

Implement:

Risk Score Engine

Formula:

30% Detection Confidence
25% Baseline Deviation
25% Claude Confidence
20% Historical Trader Risk

Implement Escalation Matrix:

0-40 → Ignore

41-70 → Review

71-85 → Compliance Case

86-95 → Case + Notifications

96-100 → Case + Notifications + Watchlist

Implement:

* Compliance Case Creation
* Watchlist Updates
* Audit Trail

Verify:

Alerts automatically escalate.

Stop.

---

# PHASE 5

Goal:

Notifications

Implement:

1. Email Notification Service
2. Slack Notification Service

Use:

Environment Variables

MAIL_USERNAME
MAIL_PASSWORD

SLACK_WEBHOOK_URL

Until credentials are provided:

Use mock implementations.

Design code so real credentials can be added later without code changes.

Persist notifications in database.

Verify notification records are generated.

Stop.

---

# PHASE 6

Goal:

Dashboard

Implement Thymeleaf Dashboard.

Pages:

* Live Trades
* Alerts
* Cases
* Watchlist
* Audit Trail

Charts:

* Alert Distribution
* Verdict Distribution
* Escalation Distribution
* Risk Trend

Add Explainability Panel:

Display:

* Pattern
* Evidence
* Metrics
* Risk Score
* Claude Reasoning
* Escalation Decision

Add Manual Buttons:

* Generate Layering
* Generate Spoofing
* Generate Wash Trading
* Generate Front Running
* Generate Pump & Dump

Verify:

End-to-end demo flow works.

Stop.

---

# PHASE 7

Goal:

Production Readiness

Implement:

* Spring Actuator
* Health Checks
* Docker Compose
* Render Deployment Config

Verify:

Application starts from:

docker compose up

Generate final README.

Stop.


# TRADE SENTINEL — FINAL HACKATHON EXECUTION PLAN

## Objective

Build an AI-powered Trade Surveillance & Alert Triage platform that:

* Ingests trade/order events
* Detects suspicious behaviour
* Uses Claude to reduce false positives
* Automatically escalates genuine cases
* Demonstrates a complete compliance workflow

---

# Judging Strategy

## Score Matrix

| Category              | Weight |
| --------------------- | ------ |
| AI Triage Quality     | 25%    |
| Pattern Detection     | 20%    |
| Automation & Workflow | 20%    |
| Working Demo          | 20%    |
| API Efficiency        | 10%    |
| Documentation         | 5%     |

### Optimization Goal

Maximize:

AI Triage + Automation + Demo

These represent:

65% of total score.

---

# MVP Architecture

Live Market Feed
→ Synthetic Order Generator
→ Detection Engine
→ Alert Generation
→ Claude Triage
→ Risk Scoring
→ Escalation Engine
→ Dashboard

---

# Scope Classification

## MUST HAVE

### Trade Ingestion

Support:

* CSV Upload
* JSON Upload
* Replay Engine
* Live Trade Simulation

### Pattern Detection

Implement:

1. Spoofing
2. Layering
3. Wash Trading

Optional:

4. Front Running

### Claude Triage

Claude only reviews alerts.

Never review trades directly.

Flow:

Trades
→ Detection
→ Alert
→ Claude

### PostgreSQL

Persist:

* Alerts
* Triage Results
* Cases
* Notifications
* Audit Trail
* Watchlist

### Dashboard

Must show:

* Live Trades
* Alerts
* Cases
* Notifications
* Risk Scores
* Claude Verdicts

### Automation

Must support:

* Compliance Case Creation
* Email Notification
* Watchlist Update

---

# Claude Cost Protection

## Budget

Available:

~$25 remaining

This is sufficient.

### Rules

Only HIGH and CRITICAL alerts reach Claude.

LOW and MEDIUM use offline triage.

### Properties

anthropic.enabled=true

anthropic.max-alerts-per-run=100

anthropic.max-daily-alert-reviews=500

anthropic.high-severity-only=true

anthropic.max-concurrent-requests=2

### Expected Flow

10000 Trades
→ 100 Alerts
→ 20 Claude Calls

Safe for budget.

---

# Risk Score Engine

## Formula

Risk Score =

30% Detection Confidence

25% Baseline Deviation

25% Claude Confidence

20% Historical Trader Risk

Range:

0-100

---

# Escalation Matrix

| Score  | Verdict        | Action                          |
| ------ | -------------- | ------------------------------- |
| 0-40   | FALSE_POSITIVE | Ignore                          |
| 41-70  | REVIEW         | Analyst Queue                   |
| 71-85  | REAL_ALERT     | Create Case                     |
| 86-95  | REAL_ALERT     | Case + Notification             |
| 96-100 | REAL_ALERT     | Case + Notification + Watchlist |

Display this table in the UI.

---

# Explainability Panel

Every alert must display:

Pattern

Evidence

Metrics

Risk Score

Claude Reasoning

Escalation Decision

Example:

Pattern:
Spoofing

Evidence:

* 85% cancellation ratio
* 620ms median cancel
* 4.2σ above baseline

Claude Reasoning:

Behaviour significantly deviates from trader baseline.

---

# Scenario Scheduler

Automatically generate:

| Scenario          | Distribution |
| ----------------- | ------------ |
| Normal Trading    | 90%          |
| Layering          | 3%           |
| Spoofing          | 2%           |
| Wash Trading      | 2%           |
| Front Running     | 1%           |
| Momentum Ignition | 1%           |
| Pump & Dump       | 1%           |

Purpose:

Keep dashboard alive.

---

# Manual Demo Buttons

Required.

Buttons:

* Generate Layering Attack
* Generate Spoofing Attack
* Generate Wash Trading
* Generate Front Running
* Generate Pump & Dump

Purpose:

Guarantee successful judge demo.

---

# Database Design

## alerts

Store:

* alertId
* traderId
* symbol
* pattern
* severity
* riskScore
* createdAt

## triage_results

Store:

* alertId
* verdict
* confidence
* fpProbability
* reason
* source

## compliance_cases

Store:

* caseId
* alertId
* status
* assignedTo

## notifications

Store:

* notificationId
* channel
* message
* status

## watchlist

Store:

* traderId
* riskScore
* addedAt

## audit_events

Store:

* alertId
* eventType
* description
* timestamp

---

# Notifications

## Mandatory

Compliance Case

## Recommended

Email

## Bonus

Slack

## Skip

Real Jira

Reason:

Too much effort for limited scoring benefit.

Simulated Compliance Cases achieve the same demonstration outcome.

---

# Dashboard Tabs

## Live Trades

Columns:

* Time
* Trader
* Symbol
* Side
* Qty
* Price
* Event Type

Auto refresh every 3 seconds.

---

## Alerts

Columns:

* Pattern
* Severity
* Risk Score
* Verdict
* Confidence
* Source

---

## Compliance Cases

Columns:

* Case ID
* Alert ID
* Status

---

## Watchlist

Columns:

* Trader
* Risk Score
* Monitoring Status

---

## Audit Trail

Columns:

* Timestamp
* Event
* Description

---

# Dashboard Charts

## Alert Distribution

LOW

MEDIUM

HIGH

CRITICAL

Bar Chart

---

## Verdict Distribution

FALSE_POSITIVE

REVIEW

REAL_ALERT

Pie Chart

---

## Escalation Distribution

Ignored

Review

Case Created

Watchlisted

Pie Chart

---

## Risk Trend

Time vs Risk Score

Line Chart

---

# Actuator & Metrics

Enable:

/actuator/health

/actuator/prometheus

Track:

* alerts.total
* triage.claude
* triage.offline
* notifications.sent

---

# Features To Remove

DO NOT BUILD

* Kafka
* Redis
* XGBoost
* Isolation Forest
* ServiceNow
* Real Jira Integration

Reason:

Low scoring value.

High implementation cost.

---

# Final Demo Flow

1. Start Replay
2. Trades Appear
3. Suspicious Scenario Generated
4. Detection Engine Creates Alert
5. Claude Reviews Alert
6. Risk Score Calculated
7. Escalation Matrix Evaluated
8. Case Created
9. Email/Slack Notification Generated
10. Trader Added To Watchlist
11. Audit Trail Updated

Judge sees:

Ingestion
→ Detection
→ Triage
→ Escalation

which directly satisfies all major judging criteria.

---

# Build Order

Phase 1

* PostgreSQL
* Replay
* Detection

Phase 2

* Claude Triage
* Risk Score
* Escalation

Phase 3

* Dashboard
* Charts
* Notifications

Phase 4

* Demo Buttons
* Scheduler
* Polish

Definition of Done:

* End-to-end demo works
* Claude only reviews alerts
* Cases created automatically
* Watchlist updates automatically
* Dashboard updates live
* PostgreSQL stores all records
* Demo can be executed repeatedly without failure

# Synthetic Data & Scenario Generation

## Mandatory

The system must continuously generate realistic trading activity.

Purpose:

- Keep dashboard active
- Demonstrate detection engine
- Demonstrate Claude triage
- Demonstrate escalation workflow
- Remove dependency on external datasets

### Scheduler

Runs every 3 seconds.

Generates:

| Scenario | Distribution |
|-----------|-----------|
| Normal Trading | 90% |
| Layering | 3% |
| Spoofing | 2% |
| Wash Trading | 2% |
| Front Running | 1% |
| Momentum Ignition | 1% |
| Pump & Dump | 1% |

### Generated Fields

- traderId
- accountId
- symbol
- side
- quantity
- price
- eventType
- timestamp

### Output

Generated events must be stored in PostgreSQL and processed exactly like real ingested data.

No special processing path.

Generated data must flow through:

Generator
→ Detection
→ Claude
→ Escalation
→ Dashboard

### Acceptance Criteria

- Live trade feed always contains activity
- Alerts appear automatically
- Dashboard charts continuously update
- Demo can run without uploaded files

### very IMP

# Manual Scenario Triggers

Required Dashboard Actions

- Generate Layering Attack
- Generate Spoofing Attack
- Generate Wash Trading
- Generate Front Running
- Generate Pump & Dump

Purpose:

Guarantee deterministic demonstrations during judging.

Acceptance:

Clicking a button generates data that results in an alert within 10 seconds.

Imagine a judge comes.

You click:

Generate Spoofing Attack

Within seconds:

Trade Generated
↓
Alert Created
↓
Claude Analysis
↓
Risk Score 92
↓
Email Sent
↓
Slack Sent
↓
Case Created
↓
Watchlist Updated

That's an end-to-end story.

Without the scheduler and manual triggers, you risk:

"No suspicious activity happened during the demo"

which is one of the most common hackathon failures.