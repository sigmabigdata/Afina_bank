-- ============================================================
-- Журнал аудита: важные события (входы, CRUD, подписи)
-- ============================================================

CREATE TABLE audit_events (
    id           BIGSERIAL PRIMARY KEY,
    event_time   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_type   VARCHAR(50) NOT NULL,
    result       VARCHAR(20) NOT NULL,   -- SUCCESS | FAIL | WARN
    actor_email  VARCHAR(500),           -- email или CN действующего лица
    actor_role   VARCHAR(30),
    actor_ip     VARCHAR(64),
    target_type  VARCHAR(50),            -- USER | DOCUMENT | SETTINGS | ...
    target_id    VARCHAR(50),            -- id или имя сущности
    target_info  VARCHAR(500),           -- человекочитаемое описание
    details      TEXT,                    -- свободный текст (ошибка, причина)
    user_agent   VARCHAR(500)
);

CREATE INDEX idx_audit_event_time  ON audit_events (event_time DESC);
CREATE INDEX idx_audit_event_type  ON audit_events (event_type);
CREATE INDEX idx_audit_actor_email ON audit_events (actor_email);
CREATE INDEX idx_audit_result      ON audit_events (result);

COMMENT ON TABLE audit_events IS 'Журнал аудита важных действий';
COMMENT ON COLUMN audit_events.event_type IS 'LOGIN_SUCCESS, SIGN_DOC, DELETE_DOC, ...';

GRANT SELECT, INSERT ON audit_events TO afina_app;
GRANT SELECT ON audit_events TO afina_auditor;
GRANT USAGE, SELECT ON SEQUENCE audit_events_id_seq TO afina_app;
