-- История мониторинга: каждый результат проверки
CREATE TABLE monitor_events (
    id           BIGSERIAL PRIMARY KEY,
    checked_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status       VARCHAR(20) NOT NULL,   -- OK | FAIL
    reason       VARCHAR(500),
    alert_sent   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_monitor_events_checked ON monitor_events (checked_at DESC);

COMMENT ON TABLE monitor_events IS 'История health-check';

-- Настройка: consecutive_fails (внутренний счётчик)
INSERT INTO app_settings (key, value, description) VALUES
    ('monitor.consecutive_fails', '0', 'Счётчик подряд идущих сбоев'),
    ('monitor.last_alert_at', '', 'Время последнего алерта (ISO)');

GRANT SELECT, INSERT, UPDATE, DELETE ON monitor_events TO afina_app;
GRANT SELECT ON monitor_events TO afina_auditor;
GRANT USAGE, SELECT ON SEQUENCE monitor_events_id_seq TO afina_app;
