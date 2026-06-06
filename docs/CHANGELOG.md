# Trade Sentinel — Implementation Changelog

> All changes made during the AI-assisted implementation session.  
> Branch: `build`  
> Date: 2026-06-06

---

## Phase 1 — Project Foundation

### `build.gradle` — Modified
- Upgraded Java 17 → **Java 21**
- Added `ext['flyway.version'] = '10.22.0'` to support **PostgreSQL 17** (Spring Boot 3.3.5 bundles Flyway 10.10 which doesn't support PG 17)
- Added `org.flywaydb:flyway-database-postgresql` — PostgreSQL dialect module for Flyway 10.x
- Added `spring-boot-starter-data-jpa` — JPA/Hibernate
- Added `org.flywaydb:flyway-core` — schema migrations
- Added `org.postgresql:postgresql` — JDBC driver
- Added `spring-boot-starter-actuator` — health + metrics
- Added `io.micrometer:micrometer-registry-prometheus` — Prometheus scrape endpoint
- Added `spring-boot-starter-thymeleaf` — server-side HTML rendering
- Added `spring-boot-starter-mail` — email notifications
- Added `com.google.guava:guava:33.2.1-jre` — Guava `RateLimiter` for Claude API
- Added Lombok (`compileOnly` + `annotationProcessor` for main and test) — removes entity boilerplate

### `src/main/resources/application.properties` — Replaced
Complete rewrite with all new properties:
```properties
# Claude
anthropic.high-severity-only=true
anthropic.max-alerts-per-run=100
anthropic.rate-limit-per-second=5

# PostgreSQL
spring.datasource.url=${DATABASE_URL:jdbc:postgresql://localhost:5432/tradesentinel}
spring.datasource.username=${DB_USER:tradesentinel}
spring.datasource.password=${DB_PASSWORD:tradesentinel}
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true

# Actuator
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.health.mail.enabled=false   ← added later to suppress mail DOWN status

# Scheduler, notifications, mail properties
```

### `.env.example` — Created
Template for all required environment variables:
```
DATABASE_URL, DB_USER, DB_PASSWORD
ANTHROPIC_API_KEY
SLACK_WEBHOOK_URL, NOTIFICATION_EMAIL
MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD
```

### `src/main/resources/db/migration/V1__init.sql` — Created
Flyway migration creating 7 tables:

| Table | Purpose |
|-------|---------|
| `order_events` | Raw trade/order event stream |
| `surveillance_alerts` | Detected suspicious-behaviour alerts |
| `triage_results` | Claude / offline triage decisions |
| `compliance_cases` | Escalated compliance cases |
| `notifications` | Email and Slack notification log |
| `watchlist_entries` | High-risk traders under monitoring |
| `audit_events` | Immutable compliance audit trail |

Plus 9 indexes for query performance.  
**Note**: JSON columns stored as `TEXT` (not JSONB) to avoid Hibernate type mismatch issues.

### Entity Classes — Created (`src/main/java/…/entity/`)

| File | Maps To |
|------|---------|
| `OrderEventEntity.java` | `order_events` |
| `SurveillanceAlertEntity.java` | `surveillance_alerts` |
| `TriageResultEntity.java` | `triage_results` |
| `ComplianceCaseEntity.java` | `compliance_cases` |
| `NotificationEntity.java` | `notifications` |
| `WatchlistEntryEntity.java` | `watchlist_entries` |
| `AuditEventEntity.java` | `audit_events` |

All use `@PrePersist`/`@PreUpdate` for automatic timestamp management. No Lombok `@Data` — uses `@Getter @Setter @NoArgsConstructor`.

### Repository Interfaces — Created (`src/main/java/…/repository/`)

| File | Key Methods |
|------|-------------|
| `OrderEventRepository` | `findTop200ByOrderByEventTimeDesc()`, `findByEventTimeAfter()` |
| `SurveillanceAlertRepository` | `findByAlertId()`, `countBySeverity()`, `countByPattern()` |
| `TriageResultRepository` | `findByAlertId()`, `countByVerdict()`, `countBySource()` |
| `ComplianceCaseRepository` | `findByCaseId()`, `findByAlertId()`, `countByStatus()` |
| `NotificationRepository` | `findTop50ByOrderByCreatedAtDesc()`, `findByStatus()` |
| `WatchlistEntryRepository` | `findByTraderId()`, `findAllByOrderByRiskScoreDesc()`, `existsByTraderId()` |
| `AuditEventRepository` | `findTop100ByOrderByEventTimeDesc()`, `findByAlertIdOrderByEventTimeDesc()` |

### `TradeSentinelApplication.java` — Modified
Added `@EnableScheduling` annotation.

### `src/test/resources/application.properties` — Created
Disables JPA, Flyway, and DataSource auto-configuration for unit tests so they run without a real PostgreSQL instance.

---

## Phase 2 — Scheduler + Detection Engine

### `SyntheticTradeGenerator.java` — Created
Generates realistic `OrderEvent` batches per scenario:
- **7 scenarios**: Normal, Layering, Spoofing, WashTrading, FrontRunning, MomentumIgnition, PumpAndDump
- **Distribution**: Normal 90%, Layering 3%, Spoofing 2%, Wash 2%, FrontRunning 1%, Momentum 1%, PumpAndDump 1%
- Uses real symbols: `RELIANCE, TCS, HDFC, INFY, ICICIBANK, WIPRO, AXISBANK, SBIN`
- Uses real exchanges: `NSE, BSE`

### `ScenarioScheduler.java` — Created
- `@Scheduled(fixedDelay = 3000)` — fires every 3 seconds
- Picks a scenario, generates events, saves to DB, feeds to `PipelineService`
- Maintains a `ConcurrentLinkedDeque<OrderEvent>` ring buffer (max 200) for the live feed endpoint
- `triggerScenario(Scenario)` — public method for manual dashboard button triggers
- `getLiveFeed()` — returns current live feed snapshot
- `@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")` — can be disabled

### `DetectionService.java` — Modified
- Added `detectFrontRunning()` method:
  - Finds large institutional executions (qty > 5× median)
  - Detects if a different trader executed the same side within 500 ms before
  - Generates `FrontRunning` alert
- Added `detectFrontRunning()` to the `detectAlerts()` stream
- Added `java.util.stream.Collectors` import

### `PersistenceService.java` — Created
Centralised DB write service to keep other services clean:
- `saveOrderEvents(events, runId)` — batch inserts to `order_events`
- `saveAlert(alert)` — idempotent insert to `surveillance_alerts`
- `updateAlertRiskScore(alertId, riskScore)` — updates risk score after computation
- `saveTriage(alertId, triage)` — idempotent insert to `triage_results`
- `createCase(alertId, priority, assignedTo)` — idempotent insert to `compliance_cases`
- `addToWatchlist(traderId, riskScore, reason)` — upsert to `watchlist_entries`
- `audit(alertId, eventType, description)` — appends to `audit_events`
- All methods use `@Transactional`

### `PipelineService.java` — Replaced
- `PersistenceService` injected `@Autowired(required = false)` — null-safe (unit tests still work)
- `EscalationService` injected `@Autowired(required = false)`
- After triage: calls `persistenceService.saveAlert()` and `persistenceService.saveTriage()`
- After triage: calls `escalationService.process()` for DB-backed risk scoring and case creation
- Added `@Slf4j` via Lombok

### `TradeSentinelController.java` — Replaced
- `ScenarioScheduler` injected `@Autowired(required = false)`
- Added `GET /api/trades/live` — returns live feed from scheduler ring buffer
- Added `POST /api/trigger/{scenario}` — manually triggers a named scenario (for demo buttons)
- All other endpoints preserved

---

## Phase 3 — Claude Triage + Risk Score + Escalation

### `TriageService.java` — Replaced
- Added `highSeverityOnly` flag — only `HIGH`/`CRITICAL` alerts sent to Claude
- Added Guava `RateLimiter` — configurable via `anthropic.rate-limit-per-second` property
- On rate limit hit: logs WARN and falls back to offline triage (no exception)
- Added `FrontRunning` and `Spoofing` offline triage branches with pattern-specific reasons
- Updated constructor signature: `(ObjectMapper, boolean, String, String, boolean, double)`

### `RiskScoreEngine.java` — Created
Formula implementation:
```
Risk Score = 30% × detectionScore
           + 25% × baselineDeviation
           + 25% × claudeConfidence
           + 20% × historicalTraderRisk
```
- Looks up `watchlist_entries` for historical risk (bonus 20 if already watchlisted)
- `decide(riskScore)` — maps score to `EscalationDecision` enum per matrix

### `EscalationService.java` — Created
- Calls `RiskScoreEngine.compute()` and `RiskScoreEngine.decide()`
- Persists risk score to `surveillance_alerts.risk_score`
- Creates compliance cases, sends notifications, adds to watchlist based on decision
- `NotificationService` injected `@Autowired(required = false)`
- Full audit trail written for every decision

### Test files — Modified
- `TriageServiceTest.java`: updated constructor call `new TriageService(..., true, 5.0)` for new params
- `PipelineServiceTest.java`: same constructor update

---

## Phase 4 — Thymeleaf Dashboard

### `DashboardController.java` — Created
Thymeleaf controller serving 5 pages:

| Route | Page | Data Loaded |
|-------|------|-------------|
| `GET /` | Live Trades | stats + chart data |
| `GET /alerts` | Alerts | last 50 alerts + triage map |
| `GET /cases` | Compliance Cases | last 50 cases |
| `GET /watchlist` | Watchlist | all entries by risk score desc |
| `GET /audit` | Audit Trail | last 100 events |
| `GET /api/charts/data` | REST (JSON) | severity dist, verdict dist, case status, risk trend |
| `GET /api/notifications/recent` | REST (JSON) | last 50 notifications |

### `src/main/resources/templates/dashboard.html` — Created
Full Thymeleaf dashboard:

**Header**: Brand, Claude API status badge (green = active, amber = offline)

**Stats bar**: Trade Events · Alerts · Open Cases · Watchlisted · Notifications · Live/3s

**Navigation tabs**: Live Trades · Alerts · Cases · Watchlist · Audit Trail

**Live Trades tab**:
- 4 Chart.js charts: Alert Severity (bar), Verdict Distribution (doughnut), Case Status (doughnut), Risk Score Trend (line)
- 6 manual trigger buttons wired to `POST /api/trigger/{scenario}`
- Live trade feed table — auto-refreshes every 3s via `fetch('/api/trades/live')`
- New rows flash with CSS animation for 2 seconds
- Escalation matrix reference table

**Alerts tab**:
- Card per alert: pattern, symbol, trader, score, severity badge, risk score pill (colour-coded)
- Explainability panel: confidence bar, FP%, triage source, case note
- Mini trigger buttons for quick demo

**Cases tab**: Table with case ID, alert ID, status badge, priority, assigned-to, created date

**Watchlist tab**: Table with trader ID, risk score bar, alert count, status, reason, date

**Audit Trail tab**: Chronological table of all system events

**JavaScript**:
- `loadHealth()` — polls `/api/health`, updates status badge
- `loadLiveFeed()` — polls `/api/trades/live` every 3s, flashes new rows
- `loadCharts()` — polls `/api/charts/data` every 10s, re-renders Chart.js
- `trigger(scenario)` — calls `POST /api/trigger/{scenario}`, reloads page after 3s

**Theme**: Dark (`#0f1117` background, `#6366f1` accent, semantic badge colours)

---

## Phase 5 — Notifications

### `NotificationService.java` — Created
Dual-channel notification service:

**Email channel**:
- Uses Spring `JavaMailSender`
- Builds structured plain-text message (alert ID, pattern, trader, risk score, verdict, reason)
- If `MAIL_USERNAME` is blank → logs `[MOCK EMAIL]`, saves record with `status=MOCK`
- If credentials present → sends via SMTP, saves with `status=SENT` or `status=FAILED`

**Slack channel**:
- Uses raw `HttpClient` POST to Slack Incoming Webhook
- Includes severity emoji (🚨 CRITICAL, ⚠️ HIGH, ℹ️ others)
- If `SLACK_WEBHOOK_URL` is blank or placeholder → logs `[MOCK SLACK]`, saves `status=MOCK`
- If webhook present → POSTs JSON payload, saves `status=SENT` or `status=FAILED`

Every attempt — sent, mock, or failed — persisted to `notifications` table.

### `EscalationService.java` — Modified
- `notificationService.notify()` called for `CASE_AND_NOTIFY` and `CASE_NOTIFY_WATCHLIST` decisions
- Audit event updated from `NOTIFICATION_QUEUED` → `NOTIFICATION_SENT`

### `DashboardController.java` — Modified
- `NotificationRepository` injected (added to constructor via `@RequiredArgsConstructor`)
- `totalNotifications` added to all `addSummaryStats()` calls
- `GET /api/notifications/recent` endpoint added

---

## Bug Fixes Applied During Startup

### Fix 1 — Flyway + PostgreSQL 17 compatibility
**Error**: `Unsupported Database: PostgreSQL 17.10`  
**Root cause**: Spring Boot 3.3.5 manages Flyway 10.10 which predates PG 17 support  
**Fix**:
```groovy
ext['flyway.version'] = '10.22.0'
implementation 'org.flywaydb:flyway-database-postgresql'
```

### Fix 2 — Flyway non-empty schema without history table
**Error**: `Found non-empty schema(s) "public" but no schema history table`  
**Root cause**: `tradesentinel` DB had a stale `test_connection` table from manual testing  
**Fix**: `DROP TABLE IF EXISTS test_connection;` — schema was otherwise empty

### Fix 3 — Mail health check pulling overall actuator status DOWN
**Error**: `mail: DOWN` due to no SMTP credentials (expected/acceptable)  
**Fix**: `management.health.mail.enabled=false` in `application.properties`

---

## Files Created (new)

```
build.gradle                                              ← updated
src/main/resources/application.properties                ← replaced
src/test/resources/application.properties                ← new (test DB exclusions)
.env.example                                              ← new
src/main/resources/db/migration/V1__init.sql             ← new

src/main/java/…/entity/
    OrderEventEntity.java
    SurveillanceAlertEntity.java
    TriageResultEntity.java
    ComplianceCaseEntity.java
    NotificationEntity.java
    WatchlistEntryEntity.java
    AuditEventEntity.java

src/main/java/…/repository/
    OrderEventRepository.java
    SurveillanceAlertRepository.java
    TriageResultRepository.java
    ComplianceCaseRepository.java
    NotificationRepository.java
    WatchlistEntryRepository.java
    AuditEventRepository.java

src/main/java/…/service/
    SyntheticTradeGenerator.java
    ScenarioScheduler.java
    PersistenceService.java
    RiskScoreEngine.java
    EscalationService.java
    NotificationService.java

src/main/java/…/web/
    DashboardController.java

src/main/resources/templates/
    dashboard.html

docs/
    IMPLEMENTATION_PLAN.md
    REQUIREMENTS.md               ← this session
    CHANGELOG.md                  ← this session
```

## Files Modified (existing)

```
src/main/java/…/TradeSentinelApplication.java      ← added @EnableScheduling
src/main/java/…/service/DetectionService.java      ← added detectFrontRunning()
src/main/java/…/service/PipelineService.java       ← added persistence + escalation wiring
src/main/java/…/service/TriageService.java         ← added rate limiter, severity filter, new patterns
src/main/java/…/web/TradeSentinelController.java   ← added /api/trades/live, /api/trigger/{scenario}
src/test/…/service/PipelineServiceTest.java        ← updated TriageService constructor call
src/test/…/service/TriageServiceTest.java          ← updated TriageService constructor call
```

---

## Final Runtime State (verified)

| Check | Result |
|-------|--------|
| `./gradlew test` | ✅ BUILD SUCCESSFUL — all tests pass |
| `./gradlew bootJar` | ✅ BUILD SUCCESSFUL |
| App starts on `:8080` | ✅ Started in ~12 seconds |
| PostgreSQL connected | ✅ HikariPool started |
| Flyway migration V1 | ✅ Applied — 7 tables created |
| Claude API | ✅ `claudeConfigured: true` |
| Scheduler running | ✅ Events ingesting every 3s |
| `/actuator/health` | ✅ UP (db, disk, ping all UP) |
| `/api/trades/live` | ✅ Returns live OrderEvent JSON |
| Dashboard at `/` | ✅ Thymeleaf rendering |
