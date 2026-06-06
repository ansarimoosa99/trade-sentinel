# Trade Sentinel MVP — Implementation Plan

> Hand this document to a new Claude Code session.  
> Work top-to-bottom; each item is self-contained unless a dependency is noted.  
> Branch: `build` (already exists — keep committing here).

---

## Current State Audit

Before starting, verify what already exists so you don't duplicate work.

| Item | Status | Notes |
|------|--------|-------|
| `GlobalExceptionHandler.java` | **DONE** | Structured error responses exist |
| `JsonOrderParser.java` | **DONE** | JSON parsing service exists |
| `BaselineService.java` | **DONE** | Basic baseline logic exists |
| `ANTHROPIC_API_KEY` via env var | **DONE** | `application.properties` uses `${ANTHROPIC_API_KEY}` |
| `POST /api/replay-json` endpoint | **VERIFY** | Parser exists; confirm controller wire-up |
| Spoofing detector | **DISABLED** | Was in DetectionService, needs re-enable + tuning |
| PostgreSQL | **MISSING** | No JPA / Flyway dependency; no repositories |
| Rate limiting on Claude | **MISSING** | TriageService calls Claude unbounded |
| Spring Actuator | **MISSING** | Not in build.gradle |
| Trades tab on dashboard | **MISSING** | index.html has one view only |
| Real-time mock data push | **MISSING** | No scheduler |
| Trader baseline in triage prompt | **MISSING** | Claude prompt has no trader baseline context |
| ML pre-filter | **MISSING** | Phase 3 item |

---

## HIGH PRIORITY — Implement in Order

---

### H1 — Verify & complete `POST /api/replay-json` endpoint

**What to do**
1. Open `TradeSentinelController.java` and confirm a `@PostMapping("/api/replay-json")` endpoint exists that calls `JsonOrderParser`.
2. If missing, add it — accept `application/json` body, parse with `JsonOrderParser`, feed into `PipelineService.run()`, return `PipelineResult`.
3. Add a basic integration smoke test in `PipelineServiceTest`.

**Acceptance**: `curl -X POST http://localhost:8080/api/replay-json -H "Content-Type: application/json" -d '[{...}]'` returns a `PipelineResult` JSON.

---

### H2 — ANTHROPIC_API_KEY: confirm no hardcoded key in Java

**What to do**
1. Run: `grep -r "sk-ant" src/` — must return nothing.
2. If any hardcoded key is found, remove it; the value in `application.properties` (`${ANTHROPIC_API_KEY}`) is already correct.
3. Add `application.properties` to `.gitignore` if it contains secrets. Use `application-local.properties.example` as the template (already present).
4. Update `README.md` with: "Set `ANTHROPIC_API_KEY` env var before running."

**Acceptance**: No API key literal in source; app starts with `ANTHROPIC_API_KEY=sk-ant-xxx ./gradlew bootRun`.

---

### H3 — Re-enable and tune Spoofing single-order detector

**What to do**
1. Open `DetectionService.java`. Look for any commented-out or stub spoofing detection.
2. Add/restore a `detectSpoofing()` method with these rules:
   - Per trader, per symbol: large order (quantity > 2× median) placed and cancelled within 500 ms while a smaller opposite-side execution fires in the same window.
   - Threshold: ≥ 3 such episodes in the observation window → `Alert` with `pattern = "Spoofing"`, `severity = HIGH`.
3. Add to `DetectionService.detectAll()`.
4. Add unit tests in `DetectionServiceTest` covering true-positive and false-positive scenarios.

**Acceptance**: Test data with spoof pattern → Alert generated; normal cancel behaviour → no alert.

---

### H4 — Rate limiting on Claude API calls (token bucket)

**What to do**
1. Add Guava to `build.gradle`:
   ```groovy
   implementation 'com.google.guava:guava:33.2.1-jre'
   ```
2. In `TriageService`, inject a `RateLimiter` via `@Bean` (default: 5 permits/second; configurable via `anthropic.rate-limit-per-second` property).
3. Before each `claudeTriage()` call: `rateLimiter.acquire()` (blocking) or return `offlineTriage()` if a non-blocking try fails and a flag `anthropic.drop-on-rate-limit=true` is set.
4. Log a WARN when rate-limited: `"Claude rate limit hit for alert {alertId} — falling back to offline triage"`.

**Acceptance**: Property `anthropic.rate-limit-per-second=1` + 10 concurrent calls → only 1 hits Claude per second; rest get offline triage; no exception thrown.

---

### H5 — Spring Boot Actuator health + metrics endpoints

**What to do**
1. Add to `build.gradle`:
   ```groovy
   implementation 'org.springframework.boot:spring-boot-starter-actuator'
   implementation 'io.micrometer:micrometer-registry-prometheus'
   ```
2. Add to `application.properties`:
   ```properties
   management.endpoints.web.exposure.include=health,info,metrics,prometheus
   management.endpoint.health.show-details=always
   ```
3. Register two custom `Counter` metrics in `PipelineService`:
   - `tradesentinel.alerts.total` (tagged by `pattern`, `severity`)
   - `tradesentinel.triage.source` (tagged by `source`: `claude` or `offline`)
4. Add a custom `HealthIndicator` bean `ClaudeHealthIndicator` that returns `UP` if `TriageService.claudeConfigured()` is true, `DOWN` otherwise.

**Acceptance**: `GET /actuator/health` returns 200 with `claude` component; `GET /actuator/prometheus` returns metric lines.

---

### H6 — PostgreSQL persistence for alerts + triage

**Dependencies**: H5 (Actuator confirms app starts cleanly before adding DB).

**What to do**

#### 6a — Add dependencies
```groovy
// build.gradle
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
implementation 'org.flywaydb:flyway-core'
runtimeOnly 'org.postgresql:postgresql'
```

#### 6b — Flyway migration `V1__init.sql`
Create `src/main/resources/db/migration/V1__init.sql` with the exact schema from `docs/SCALING_ROADMAP.md` (tables: `order_events`, `alerts`, `triage_results`, `escalation_actions`).

#### 6c — JPA Entities
Create entity classes matching the schema:
- `AlertEntity.java` — `@Entity @Table(name="alerts")`; fields mirror `Alert` record
- `TriageResultEntity.java` — `@Entity @Table(name="triage_results")`; FK to `AlertEntity`

Keep existing `Alert` and `TriageResult` **records** (they are the domain model); entities are the persistence layer only.

#### 6d — Spring Data Repositories
```java
@Repository
public interface AlertRepository extends JpaRepository<AlertEntity, String> {
    List<AlertEntity> findByTraderId(String traderId);
    List<AlertEntity> findBySeverity(String severity);
}

@Repository
public interface TriageResultRepository extends JpaRepository<TriageResultEntity, Long> {
    Optional<TriageResultEntity> findByAlertId(String alertId);
}
```

#### 6e — Wire up PipelineService
After `triageService.triage(alert)` returns a `TriageResult`, save both entity records.  
Wrap in `@Transactional`. Do NOT break the existing in-memory flow — persistence is additive.

#### 6f — application.properties additions
```properties
spring.datasource.url=${DATABASE_URL:jdbc:postgresql://localhost:5432/tradesentinel}
spring.datasource.username=${DB_USER:tradesentinel}
spring.datasource.password=${DB_PASSWORD:}
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
```

#### 6g — application-local.properties.example
Update the example file with the DB vars so devs know what to set.

**Acceptance**: With a local Postgres running, `./gradlew bootRun` → replay CSV → rows appear in `alerts` and `triage_results` tables.

---

### H7 — Triage quality: trader baseline + structured verdict

This is the highest-impact quality change per the requirements.

**What to do**

#### 7a — Trader baseline resource file
Create `src/main/resources/baselines/trader_baseline_template.json`:
```json
{
  "traderId": "TRADER_001",
  "windowDays": 30,
  "cancelRatioPct": 12,
  "avgOrderSize": 500,
  "avgDailyOrders": 45,
  "typicalSymbols": ["AAPL", "MSFT"],
  "historicalAlerts": 0
}
```
`BaselineService` should load per-trader JSON from this directory (filename = `{traderId}.json`), falling back to the template if no file exists.

#### 7b — Enrich triage prompt with baseline
In `TriageService.claudeTriage()`, call `baselineService.getBaseline(alert.traderId())` and inject into the prompt:

```
Trader Baseline (30-day historical):
  - Normal cancel ratio: {baseline.cancelRatioPct}%
  - Average order size: {baseline.avgOrderSize}
  - Avg daily orders: {baseline.avgDailyOrders}
  - Historical alerts: {baseline.historicalAlerts}

Current alert metrics: {alert.metrics}

Given the deviation from baseline, determine:
1. Is this a REAL alert or FALSE POSITIVE?
2. Provide a confidence score (0-100).
3. Provide a human-readable verdict in this format:
   Verdict: REAL_ALERT | FALSE_POSITIVE
   Confidence: XX%
   Reason: [2-3 sentences comparing current behaviour to baseline]
```

#### 7c — Update TriageResult model
Add fields to `TriageResult`:
```java
public record TriageResult(
    String verdict,         // "REAL_ALERT" | "FALSE_POSITIVE" | "REVIEW"
    int confidence,         // 0-100
    int fpProbability,      // 0-100 (inverse of confidence for real alerts)
    String reason,
    List<String> riskFactors,
    List<String> fpFactors,
    List<String> recommendedActions,
    String caseNote,
    String source           // "claude" | "offline"
) {}
```

#### 7d — Update offline triage to match new format
`offlineTriage()` should also produce `verdict = "REAL_ALERT"` or `"FALSE_POSITIVE"` and a `confidence` value (score ≥ 75 → REAL_ALERT/confidence=score; else FALSE_POSITIVE/confidence=100-score).

**Acceptance**: Triage result JSON shows `verdict`, `confidence`, and `reason` citing the baseline deviation.

---

### H8 — Dashboard: Trades tab + Alerts tab (real-time)

**What to do**

#### 8a — Add mock real-time data scheduler
Create `MockDataScheduler.java`:
```java
@Component
public class MockDataScheduler {
    @Scheduled(fixedDelay = 3000)  // every 3 seconds
    public void pushMockTrade() { ... }
}
```
Enable scheduling in `TradeSentinelApplication`: `@EnableScheduling`.  
The scheduler generates a random `OrderEvent`, stores it in a thread-safe `ConcurrentLinkedDeque<OrderEvent>` (max 200 entries).

Add `@GetMapping("/api/trades/live")` in `TradeSentinelController` returning the deque contents as JSON.

#### 8b — Update `index.html` — add tabbed navigation
Replace the single-view dashboard with two tabs:

**Tab 1 — Live Trades**
- Table columns: `Time | Trader | Symbol | Side | Qty | Price | Type`
- Auto-refreshes via `setInterval(() => fetch('/api/trades/live'), 3000)`
- Highlights NEW rows for 2 seconds (CSS flash animation)

**Tab 2 — Alerts**  
- Existing alert display, unchanged in function
- Each row shows: `Pattern | Trader | Symbol | Severity | Score | Verdict | Confidence | Source`
- `Verdict` column: green badge for FALSE_POSITIVE, red for REAL_ALERT, amber for REVIEW

#### 8c — CSS & layout
Keep the existing dark-theme style. Add:
- Tab bar using plain CSS (no framework needed)
- Severity badge colours: CRITICAL=red, HIGH=orange, MEDIUM=amber, LOW=blue
- Verdict badge colours as above

**Acceptance**: Open browser → two tabs visible → Live Trades auto-updates every 3 s → Alerts tab shows structured verdict/confidence.

---

## MEDIUM PRIORITY — Implement after all High items are green

---

### M1 — Kafka consumer for live order event ingestion

**Dependencies**: H6 (persistence must exist first).

**What to do**
1. Add Spring Kafka:
   ```groovy
   implementation 'org.springframework.kafka:spring-kafka'
   ```
2. Create `KafkaOrderConsumer.java` — listens to topic `trade-events`, deserialises `OrderEvent`, feeds `PipelineService.runSingle(event)`.
3. Add Kafka properties to `application.properties` (broker URL via `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`).
4. Make Kafka auto-configuration conditional: `@ConditionalOnProperty(name="kafka.enabled", havingValue="true", matchIfMissing=false)` so the app still starts without Kafka.

---

### M2 — Redis for per-trader sliding-window state

**Dependencies**: H3 (spoofing detector needs this for production accuracy).

**What to do**
1. Add `spring-boot-starter-data-redis`.
2. Create `TraderWindowStore.java`: uses `RedisTemplate<String, List<OrderEvent>>` to maintain a 60-second sliding window per `traderId`.
3. `DetectionService` currently uses in-memory `Map` — add a `@ConditionalOnBean(RedisTemplate.class)` path that delegates to `TraderWindowStore`.
4. Make Redis optional same as Kafka above.

---

### M3 — Front-running detector

**What to do**
Add `detectFrontRunning()` to `DetectionService`:
- Group events by symbol in a 500 ms window.
- If a large institutional order (qty > 5× median) arrives, then within the next 300 ms a different trader from the same account group executes on the same side → flag as `"FrontRunning"` alert.
- Add unit tests.

---

### M4 — Wash-trading beneficial owner enrichment

**What to do**
1. Create `src/main/resources/beneficial_owners.json` — maps `accountId` → `beneficialOwnerId`.
2. In `DetectionService.detectWashTrading()`, cross-reference `accountId` via the JSON map.
3. Upgrade the alert: if the buy and sell accounts share the same beneficial owner, set `severity = CRITICAL`.

---

### M5 — Slack / PagerDuty / Email notifications

**What to do**
1. Create `NotificationService.java` with three optional channels, each activated by a property:
   - `notifications.slack.webhook-url` → POST JSON to Slack Incoming Webhook
   -  teams as well
   - `notifications.email.to` 'rohan.bhalerao@wissen.com' + SMTP properties → Spring Mail
2. `PipelineService` calls `notificationService.notify(triagedAlert)` after triage. Only sends if `verdict = REAL_ALERT` and `severity IN (HIGH, CRITICAL)`.
3. Add `spring-boot-starter-mail` dependency for email.

---

### M6 — Jira / ServiceNow case creation webhook

**What to do**
1. Create `CaseService.java`.
2. On REAL_ALERT + CRITICAL → POST to `${jira.webhook-url}` with structured JSON (trader, symbol, pattern, confidence, triage reason).
3. Store the returned `case_id` in `escalation_actions` table.
4. Configurable via properties; no-op if URL is blank.

---

## ML Pre-filter (Phase 3 — Lower Priority)

### ML1 — IsolationForest + XGBoost pre-filter

This requires a Python sidecar or a Java ML library. Recommended approach for this monorepo:

**Option A (Recommended)**: Use the `smile` library (Java ML):
```groovy
implementation 'com.github.haifengl:smile-core:3.1.0'
```
Train an `IsolationForest` on the order features (cancel ratio, order size, frequency) using the existing replay data. If anomaly score < threshold → skip Claude triage → use offline triage. Retrain weekly.

**Option B**: Python microservice (`ml-service/`) called via HTTP. More accurate but adds operational complexity.

---

## Testing Checklist

After each High-priority item, run:
```bash
./gradlew test
```
Target: **all existing tests pass** + new tests added for each feature.

Final gate before PR to `main`:
- [ ] `./gradlew test` — green
- [ ] `./gradlew bootRun` with `ANTHROPIC_API_KEY` set — starts without errors
- [ ] CSV replay returns alerts with `verdict` + `confidence`
- [ ] `/api/trades/live` returns JSON
- [ ] `/actuator/health` returns UP
- [ ] `/actuator/prometheus` returns metrics
- [ ] Dashboard loads in browser — both tabs visible, data populating

---

## Environment Variables Reference

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `ANTHROPIC_API_KEY` | No | — | Enables Claude triage |
| `DATABASE_URL` | For H6 | `jdbc:postgresql://localhost:5432/tradesentinel` | PostgreSQL |
| `DB_USER` | For H6 | `tradesentinel` | DB username |
| `DB_PASSWORD` | For H6 | — | DB password |
| `KAFKA_BOOTSTRAP_SERVERS` | For M1 | `localhost:9092` | Kafka broker |
| `REDIS_URL` | For M2 | `localhost:6379` | Redis |

---

## File Locations Quick Reference

| Component | File | Action |
|-----------|------|--------|
| Controller | `src/main/java/…/web/TradeSentinelController.java` | Add `/api/trades/live`, verify `/api/replay-json` |
| Detection | `src/main/java/…/service/DetectionService.java` | Add spoofing, front-running |
| Triage | `src/main/java/…/service/TriageService.java` | Rate limiter, baseline prompt, new verdict format |
| Baseline | `src/main/java/…/service/BaselineService.java` | Load per-trader JSON |
| Pipeline | `src/main/java/…/service/PipelineService.java` | Wire persistence, notifications |
| Scheduler | `src/main/java/…/service/MockDataScheduler.java` | **CREATE NEW** |
| Dashboard | `src/main/resources/static/index.html` | Two-tab layout, live trades table |
| DB Migration | `src/main/resources/db/migration/V1__init.sql` | **CREATE NEW** |
| JPA Entities | `src/main/java/…/entity/AlertEntity.java` etc. | **CREATE NEW** |
| Baselines | `src/main/resources/baselines/TRADER_001.json` etc. | **CREATE NEW** |
| build.gradle | root | Add JPA, Flyway, Postgres, Actuator, Guava, Micrometer |
