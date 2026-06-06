-- ─────────────────────────────────────────────────────────────────────────────
-- Trade Sentinel — Initial Schema
-- ─────────────────────────────────────────────────────────────────────────────

-- Raw trade/order event stream
CREATE TABLE order_events (
    id          BIGSERIAL PRIMARY KEY,
    order_id    TEXT        NOT NULL,
    trader_id   TEXT        NOT NULL,
    account_id  TEXT,
    symbol      TEXT        NOT NULL,
    exchange    TEXT,
    side        TEXT        NOT NULL,
    quantity    INT         NOT NULL,
    price       DECIMAL(18,6) NOT NULL,
    event_type  TEXT        NOT NULL,
    event_time  TIMESTAMPTZ NOT NULL,
    run_id      TEXT,
    ingested_at TIMESTAMPTZ DEFAULT now()
);

-- Detected suspicious-behaviour alerts
CREATE TABLE surveillance_alerts (
    id          BIGSERIAL PRIMARY KEY,
    alert_id    TEXT        UNIQUE NOT NULL,
    pattern     TEXT        NOT NULL,
    trader_id   TEXT        NOT NULL,
    account_id  TEXT,
    symbol      TEXT        NOT NULL,
    severity    TEXT        NOT NULL,
    score       INT         NOT NULL,
    risk_score  INT         NOT NULL DEFAULT 0,
    metrics     TEXT,           -- JSON string
    status      TEXT        NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ DEFAULT now()
);

-- Claude / offline triage decisions
CREATE TABLE triage_results (
    id                  BIGSERIAL PRIMARY KEY,
    alert_id            TEXT        NOT NULL REFERENCES surveillance_alerts(alert_id),
    verdict             TEXT        NOT NULL,
    confidence          INT         NOT NULL,
    fp_probability      INT         NOT NULL,
    reason              TEXT,
    case_note           TEXT,
    source              TEXT        NOT NULL,
    risk_factors        TEXT,       -- JSON array string
    fp_factors          TEXT,       -- JSON array string
    recommended_actions TEXT,       -- JSON array string
    triaged_at          TIMESTAMPTZ DEFAULT now()
);

-- Escalated compliance cases
CREATE TABLE compliance_cases (
    id          BIGSERIAL PRIMARY KEY,
    case_id     TEXT        UNIQUE NOT NULL,
    alert_id    TEXT        NOT NULL REFERENCES surveillance_alerts(alert_id),
    status      TEXT        NOT NULL DEFAULT 'OPEN',
    assigned_to TEXT,
    priority    TEXT,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);

-- Notification records (email, Slack)
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    notification_id TEXT        UNIQUE NOT NULL,
    alert_id        TEXT,
    channel         TEXT        NOT NULL,
    recipient       TEXT,
    message         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- Traders under elevated monitoring
CREATE TABLE watchlist_entries (
    id           BIGSERIAL PRIMARY KEY,
    trader_id    TEXT        UNIQUE NOT NULL,
    risk_score   INT         NOT NULL DEFAULT 0,
    alert_count  INT         NOT NULL DEFAULT 0,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',
    added_reason TEXT,
    added_at     TIMESTAMPTZ DEFAULT now(),
    updated_at   TIMESTAMPTZ DEFAULT now()
);

-- Immutable compliance audit trail
CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    alert_id    TEXT,
    event_type  TEXT        NOT NULL,
    description TEXT        NOT NULL,
    actor       TEXT        NOT NULL DEFAULT 'SYSTEM',
    event_time  TIMESTAMPTZ DEFAULT now()
);

-- ─── Indexes ──────────────────────────────────────────────────────────────────
CREATE INDEX idx_oe_trader_time   ON order_events(trader_id, event_time DESC);
CREATE INDEX idx_oe_symbol        ON order_events(symbol);
CREATE INDEX idx_sa_trader        ON surveillance_alerts(trader_id);
CREATE INDEX idx_sa_created       ON surveillance_alerts(created_at DESC);
CREATE INDEX idx_sa_severity      ON surveillance_alerts(severity);
CREATE INDEX idx_tr_alert         ON triage_results(alert_id);
CREATE INDEX idx_cc_alert         ON compliance_cases(alert_id);
CREATE INDEX idx_ae_alert         ON audit_events(alert_id);
CREATE INDEX idx_ae_time          ON audit_events(event_time DESC);
