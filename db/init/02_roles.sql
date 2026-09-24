-- Роли с разными правами — принцип наименьших привилегий.
-- app_user — уже создаётся через env (POSTGRES_USER).

-- Роль для миграций (Flyway) — может менять схему
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_migrator') THEN
    CREATE ROLE afina_migrator LOGIN PASSWORD 'migrator_password_change_me';
  END IF;
END
$$;

-- Роль для приложения — только DML, без DDL
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_app') THEN
    CREATE ROLE afina_app LOGIN PASSWORD 'app_password_change_me';
  END IF;
END
$$;

-- Роль для аудита — только чтение
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'afina_auditor') THEN
    CREATE ROLE afina_auditor LOGIN PASSWORD 'auditor_password_change_me';
  END IF;
END
$$;

-- Права
GRANT CONNECT ON DATABASE afina_db TO afina_migrator, afina_app, afina_auditor;
GRANT USAGE ON SCHEMA public TO afina_migrator, afina_app, afina_auditor;

-- Migrator может всё
GRANT CREATE, USAGE ON SCHEMA public TO afina_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO afina_auditor;
