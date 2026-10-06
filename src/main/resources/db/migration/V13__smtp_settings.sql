-- ============================================================
-- SMTP-настройки, редактируемые из админки
-- ============================================================
-- Если значения пусты — используются spring.mail.* из .env.prod

INSERT INTO app_settings (key, value, description) VALUES
    ('smtp.host',     '', 'SMTP-хост (пусто = из .env.prod)'),
    ('smtp.port',     '465', 'SMTP-порт'),
    ('smtp.username', '', 'SMTP-логин'),
    ('smtp.password', '', 'SMTP-пароль (AES-GCM в БД)'),
    ('smtp.from',     '', 'Email отправителя'),
    ('smtp.ssl',      'true', 'SSL (true) / STARTTLS (false)')
ON CONFLICT (key) DO NOTHING;
