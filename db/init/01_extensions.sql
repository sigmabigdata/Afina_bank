-- Расширения для безопасности и мониторинга.
-- Ставятся один раз при первом старте контейнера.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;  -- мониторинг запросов
CREATE EXTENSION IF NOT EXISTS pgcrypto;            -- криптография
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";         -- UUID-генерация
-- pgAudit — раскомментировать, когда настроим официально (в dev не обязательно)
-- CREATE EXTENSION IF NOT EXISTS pgaudit;
