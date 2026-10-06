-- Настройки retention для аудита
INSERT INTO app_settings (key, value, description) VALUES
    ('audit.retention_days', '365', 'Хранить события аудита N дней (0 = вечно)')
ON CONFLICT (key) DO NOTHING;
