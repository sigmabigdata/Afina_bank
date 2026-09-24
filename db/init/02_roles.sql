-- ============================================================
-- Афина · Роли и права (init-скрипт)
-- ============================================================
-- Выполняется один раз при первом старте PostgreSQL
-- от имени суперпользователя (POSTGRES_USER).
-- ============================================================

-- ---- Роли ----
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_migrator') THEN
    CREATE ROLE afina_migrator LOGIN PASSWORD 'migrator_password_change_me';
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_app') THEN
    CREATE ROLE afina_app LOGIN PASSWORD 'app_password_change_me';
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_auditor') THEN
    CREATE ROLE afina_auditor LOGIN PASSWORD 'auditor_password_change_me';
  END IF;
END $$;

-- ---- Владение схемой ----
-- ВАЖНО: мигратор должен быть владельцем схемы public,
-- чтобы Flyway мог применять DDL, включая COMMENT ON SCHEMA.
-- Это делается от имени суперпользователя.
ALTER SCHEMA public OWNER TO afina_migrator;

-- ---- Права на БД и схему ----
GRANT CONNECT ON DATABASE afina_db TO afina_migrator, afina_app, afina_auditor;
GRANT USAGE   ON SCHEMA public    TO afina_app, afina_auditor;
GRANT CREATE, USAGE ON SCHEMA public TO afina_migrator;

-- ---- Права по умолчанию для будущих объектов ----
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO afina_auditor;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT ON SEQUENCES TO afina_auditor;
