-- ============================================================
-- Настройки приложения (key-value) — редактируемые из админки
-- ============================================================

CREATE TABLE app_settings (
    key         VARCHAR(100) PRIMARY KEY,
    value       TEXT,
    description VARCHAR(500),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(255)
);

COMMENT ON TABLE app_settings IS 'Настройки приложения, редактируемые из админки';
COMMENT ON COLUMN app_settings.updated_by IS 'Кто последний изменил (email админа)';

-- Начальные значения (по умолчанию)
INSERT INTO app_settings (key, value, description) VALUES
    ('monitor.mail_to',    '', 'Email для алертов мониторинга (пусто = MAIL_FROM)'),
    ('monitor.enabled',    'true', 'Мониторинг: вкл/выкл'),
    ('revocation.mode',    'soft', 'Проверка отзыва: strict|soft|off'),
    ('auth.rate_limit.login',    '5',  'Максимум попыток входа с одного IP+email'),
    ('auth.rate_limit.window_min','15', 'Окно rate-limit в минутах'),
    ('signature.require_crl', 'false', 'Требовать CRL при проверке подписи');

-- Права
GRANT SELECT, INSERT, UPDATE, DELETE ON app_settings TO afina_app;
GRANT SELECT ON app_settings TO afina_auditor;
