# Trade Sentinel

**AI-Powered Trade Surveillance & Compliance Automation Platform**

A production-grade Spring Boot application that monitors live order flow, detects market manipulation patterns in real time, triages alerts with Claude AI, and automates compliance workflows — cases, notifications, watchlist management, and immutable audit trails.

Built for the Wissen Technology Hackathon 2026.

---

## What It Does

```
Live Order Flow → 5 Pattern Detectors → Claude AI Triage → Risk Scoring → Compliance Workflow
```

Every trade event passes through five detection algorithms. When suspicious patterns are found, Claude (or an offline rule engine) triages the alert, assigns a risk score, and the system automatically decides whether to file a compliance case, notify the team via email and Slack, and add the trader to a watchlist — all logged to an immutable audit trail.

---

## Features

| Feature | Details |
|---|---|
| **Pattern Detection** | Layering, Spoofing, Wash Trading, Front Running, Momentum Ignition / Pump & Dump |
| **AI Triage** | Claude Sonnet — verdict, confidence, false-positive probability, case note, recommended actions |
| **Offline Triage** | Deterministic rule fallback — identical output shape, no API key needed |
| **Risk Scoring** | 0–100 composite score from detection confidence, baseline deviation, AI confidence, trader history |
| **Compliance Cases** | P0 / P1 assigned to Surveillance Desk or Compliance Head |
| **Notifications** | Email (SMTP) + Slack (webhook) — MOCK fallback when credentials absent |
| **Watchlist** | Auto-adds extreme-risk traders; historical risk elevates future scoring |
| **Audit Trail** | Immutable PostgreSQL log with server-side filter, full-text search, pagination |
| **Live Dashboard** | Dark/light theme, real-time feed, charts, toast notifications |
| **CSV & JSON Ingestion** | Upload CSV or JSON order events; manual scenario triggers |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       Spring Boot 3.3.5                         │
│                                                                 │
│  ScenarioScheduler (3 s tick)                                   │
│       │                                                         │
│       ▼                                                         │
│  SyntheticTradeGenerator ──► PersistenceService ──► order_events│
│       │                                                         │
│       ▼                                                         │
│  PipelineService                                                │
│       ├── DetectionService (5 algorithms)                       │
│       │       ├── Layering detector                             │
│       │       ├── Spoofing detector                             │
│       │       ├── Wash Trading detector                         │
│       │       ├── Front Running detector                        │
│       │       └── Momentum Ignition detector                    │
│       │                                                         │
│       ├── TriageService                                         │
│       │       ├── Claude Sonnet (if API key present)            │
│       │       └── Offline rule triage (deterministic fallback)  │
│       │                                                         │
│       └── EscalationService                                     │
│               ├── RiskScoreEngine (0–100 composite)            │
│               ├── PersistenceService → compliance_cases         │
│               ├── NotificationService → Email + Slack           │
│               ├── PersistenceService → watchlist_entries        │
│               └── PersistenceService → audit_events             │
│                                                                 │
│  DashboardController ──► Thymeleaf dashboard (dark / light)     │
│  REST API  /api/audit · /api/events/recent · /api/charts/data   │
└─────────────────────────────────────────────────────────────────┘
          │
          ▼
   PostgreSQL 17  (7 tables, Flyway migrations)
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
| Observability | Spring Actuator, SLF4J + Logback (rolling file) |

---

## Quick Start

### Prerequisites

- Java 21+
- PostgreSQL 17 running locally (or via Docker)
- Database `tradesentinel` with user `tradesentinel` / password `tradesentinel`

```sql
CREATE DATABASE tradesentinel;
CREATE USER tradesentinel WITH PASSWORD 'tradesentinel';
GRANT ALL PRIVILEGES ON DATABASE tradesentinel TO tradesentinel;
```

### Run

```powershell
.\gradlew.bat bootRun
```

Open `http://localhost:8080`

The scheduler starts immediately. Within 30 seconds the dashboard shows live trade events, alerts, and audit entries.

### Build a runnable JAR

```powershell
.\gradlew.bat clean bootJar
java -jar build\libs\trade-sentinel-0.0.1-SNAPSHOT.jar
```

---

## Configuration

All settings are in `src/main/resources/application.properties`.
Override via environment variables or a local `.env` file.

### Database

```properties
spring.datasource.url=${DATABASE_URL:jdbc:postgresql://localhost:5432/tradesentinel}
spring.datasource.username=${DB_USER:tradesentinel}
spring.datasource.password=${DB_PASSWORD:tradesentinel}
```

### Claude AI

Works fully without a key (offline triage mode). To enable Claude:

```properties
anthropic.enabled=true
anthropic.api-key=${ANTHROPIC_API_KEY:}
anthropic.model=claude-sonnet-4-20250514
anthropic.high-severity-only=true
anthropic.rate-limit-per-second=5
```

Set the environment variable before running:

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
.\gradlew.bat bootRun
```

### Email (optional)

```properties
spring.mail.host=${MAIL_HOST:smtp.gmail.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:}
spring.mail.password=${MAIL_PASSWORD:}
notifications.email.to=${NOTIFICATION_EMAIL:compliance@tradesentinel.local}
```

When `MAIL_USERNAME` is blank the service logs `[MOCK EMAIL]` and records `status=MOCK` in the database — no crash, no config required.

### Slack (optional)

```properties
notifications.slack.webhook-url=${SLACK_WEBHOOK_URL:}
```

When blank: `[MOCK SLACK]` is logged, `status=MOCK` is recorded. No crash.

### Scheduler

```properties
scheduler.enabled=true
scheduler.interval-ms=3000
```

---

## Dashboard Pages

| URL | What You See |
|---|---|
| `/` | Live trade feed, 4 charts, scenario trigger buttons, CSV upload |
| `/alerts` | Top 50 recent alerts with AI triage details |
| `/cases` | Open compliance cases with priority and assignee |
| `/watchlist` | High-risk traders sorted by risk score |
| `/audit` | Server-side filtered, searchable, paginated audit trail |

---

## REST API

| Endpoint | Description |
|---|---|
| `GET /api/health` | Runtime status, Claude configuration |
| `GET /api/sample` | Bundled sample CSV |
| `POST /api/replay` | Run detection pipeline on CSV data |
| `POST /api/replay-json` | Run detection pipeline on JSON events |
| `GET /api/trades/live` | Live trade feed (last 200 events) |
| `POST /api/trigger/{scenario}` | Manual scenario trigger |
| `GET /api/audit` | Server-side filtered audit trail with pagination |
| `GET /api/events/recent?since=<epoch>` | New audit events since timestamp (for toasts) |
| `GET /api/charts/data` | Chart datasets for dashboard |
| `GET /api/notifications/recent` | Recent notification log |

### Audit API

```
GET /api/audit?eventType=ALERT&searchText=T-001&page=0&size=50
```

All parameters optional. `eventType` is a substring match (`ALERT` matches `ALERT_CREATED`, `ALERT_REVIEWED`, `ALERT_IGNORED`). Searches across all database records — not just the currently visible page.

---

## Detection Scenarios

### Auto-Scheduled (probability per tick)

| Scenario | Probability | Typical Score |
|---|---|---|
| Normal trade | 93% | No alert |
| Layering | 2% | 99 |
| Wash Trading | 2% | 98 |
| Spoofing | 1% | 95 |
| Front Running | 1% | 90 |
| Momentum Ignition | 1% | 97 |

### Manual Triggers (dashboard buttons)

All six scenarios including Pump & Dump are available as one-click buttons.

---

## Escalation Matrix

| Detection Score | Risk Score | Decision | Outcome |
|---|---|---|---|
| Any | 0–42 | IGNORE | Audit: `ALERT_IGNORED` |
| Any | 43–78 | REVIEW | Audit: `ALERT_REVIEWED` (no case) |
| Any | 79–87 | CASE | P1 case → Surveillance Desk L2 |
| Any | 88–95 | CASE_AND_NOTIFY | P1 case + email + Slack |
| Any | 96–100 | CASE_NOTIFY_WATCHLIST | P0 case + email + Slack + watchlist |
| **≥ 99** (override) | any | CASE_NOTIFY_WATCHLIST | Layering always watchlists |
| **= 98** (override) | any | CASE_AND_NOTIFY | Wash Trading always notifies |

---

## CSV Schema

```csv
order_id,trader_id,account_id,symbol,exchange,side,quantity,price,event_type,event_time
ORD-001,T-001,ACC-T-001,RELIANCE,NSE,BUY,10000,2450.50,NEW,2026-06-07T09:15:00.000Z
ORD-001,T-001,ACC-T-001,RELIANCE,NSE,BUY,10000,2450.50,EXECUTE,2026-06-07T09:15:00.100Z
```

`event_type` values: `NEW`, `MODIFY`, `CANCEL`, `EXECUTE`

---

## Detailed Documentation

| Document | Contents |
|---|---|
| [`docs/ALERT_FLOW.md`](docs/ALERT_FLOW.md) | Full alert lifecycle — detection, triage, scoring, escalation, relationships |
| [`docs/API.md`](docs/API.md) | REST endpoint reference with request/response shapes |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | System design and component map |

---

## Known Limits

- No real exchange feed; synthetic data only unless CSV is uploaded.
- Baselines are computed from the current batch — not a historical 30-day warehouse.
- Claude triage requires network access and a valid API key; offline fallback is always available.
- Email/Slack send `MOCK` when credentials are absent — no real delivery without valid config.
