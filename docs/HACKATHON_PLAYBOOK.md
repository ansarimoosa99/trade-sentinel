# Trade Sentinel Hackathon Playbook

## Goal

Trade Sentinel is a compact surveillance assistant for the complete hackathon flow: ingest order events, replay them, detect suspicious patterns, triage with Claude or deterministic fallback, and trigger workflow actions for compliance teams.

## Supported Surveillance Patterns

### Layering / Spoofing

The detector looks for many large visible orders that are cancelled quickly, plus opposite-side executions in the same trader-symbol pair. Important metrics include cancellation ratio, median cancel time, large cancelled order count, and cancellation lift versus sample baseline.

### Wash Trading

The detector looks for repeated buy/sell execution cycles by the same trader and symbol, with near-matched quantities inside short time gaps. Important metrics include cycle count, window seconds, total executed quantity, and average gap.

### Momentum Ignition / Price Spike Manipulation

The detector looks for rapid same-side executions that create a meaningful price move in a short window. Important metrics include aggressive execution count, dominant side, price move percentage, total executed quantity, and execution window.

## False-Positive Reduction

The system separates detection from triage. Detectors generate candidate alerts; triage evaluates whether the evidence is strong enough to escalate. The triage output always includes:

- Risk factors
- False-positive factors
- Confidence
- False-positive probability
- Recommended actions
- Analyst-ready case note

This structure helps judges see that the system is not blindly escalating every rule hit.

## Claude Prompt Strategy

Claude receives compact JSON for one alert at a time. The prompt asks Claude to behave as a compliance analyst, evaluate evidence quality, separate suspicious factors from benign explanations, and return only structured JSON. This keeps API usage purposeful and makes the UI deterministic.

Offline fallback returns the same shape as Claude output, so the demo works without a key.

## Workflow Design

Escalated alerts create:

- Compliance case assigned to Surveillance Desk L2
- Compliance notification
- Watchlist update for enhanced monitoring

Review alerts create:

- Pending review case
- Analyst false-positive task

Ignored alerts create:

- Suppression record
- Audit log entry

## Judge Talking Points

- The app maps directly to all four core deliverables.
- The dashboard shows the full story without switching tools.
- Claude is used efficiently: one call per alert, no repeated calls during replay.
- The fallback mode is transparent and demo-safe.
- The architecture can naturally evolve to Kafka, a database, real case-management tools, and historical baselines.

## Future Enhancements

- Kafka or Pulsar ingestion from market/order feeds.
- Historical trader-symbol baseline store.
- Real Slack/Jira/ServiceNow integrations.
- Network analysis for coordinated traders.
- Additional detectors: marking the close, front running, quote stuffing, insider trading, pump and dump.
- Analyst feedback loop to tune thresholds and reduce false positives.
