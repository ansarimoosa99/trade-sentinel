# Trade Sentinel API

Base URL:

```text
http://localhost:8080
```

## Health

```http
GET /api/health
```

Response:

```json
{
  "ok": true,
  "claudeConfigured": false
}
```

## Sample CSV

```http
GET /api/sample
```

Response:

```json
{
  "csv": "order_id,trader_id,..."
}
```

## Replay

```http
POST /api/replay
Content-Type: application/json
```

Request:

```json
{
  "csv": "order_id,trader_id,account_id,symbol,exchange,side,quantity,price,event_type,event_time\n...",
  "replayDelayMs": 0
}
```

If `csv` is omitted or blank, the bundled sample data is used.

Response top-level shape:

```json
{
  "summary": {
    "eventsIngested": 37,
    "alertsGenerated": 3,
    "highSeverity": 3,
    "escalated": 3,
    "review": 0,
    "ignored": 0,
    "triageSource": "offline"
  },
  "events": [],
  "alerts": [],
  "escalations": []
}
```

Alert triage shape:

```json
{
  "alert": {
    "alert_id": "A-0001",
    "pattern": "Layering / Spoofing",
    "trader_id": "T-4821",
    "account_id": "A-771",
    "symbol": "HDFCBANK",
    "severity": "HIGH",
    "score": 99,
    "metrics": {},
    "evidence": [],
    "created_at": "2026-06-06T..."
  },
  "triage": {
    "verdict": "ESCALATE",
    "confidence": 96,
    "false_positive_probability": 3,
    "reason": "Short analyst reason.",
    "riskFactors": [],
    "falsePositiveFactors": [],
    "recommendedActions": [],
    "caseNote": "Analyst-ready case note.",
    "source": "offline-rule-triage"
  }
}
```

Workflow action shape:

```json
{
  "id": "A-0001-ACT-01",
  "type": "CASE_CREATED",
  "target": "CASE-A-0001",
  "status": "OPEN",
  "owner": "Surveillance Desk L2",
  "priority": "P1",
  "sla": "2 hours",
  "createdAt": "2026-06-06T..."
}
```
