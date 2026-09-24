-- ============================================================
-- Восстановление владельцев и прав после pg_restore.
-- Идемпотентно: можно запускать многократно.
-- ============================================================

DO $$
DECLARE
    r RECORD;
BEGIN
    -- 1. Все таблицы в схеме public → владелец afina_migrator
    FOR r IN
        SELECT tablename FROM pg_tables WHERE schemaname = 'public'
    LOOP
        EXECUTE 'ALTER TABLE public.' || quote_ident(r.tablename)
                || ' OWNER TO afina_migrator';
    END LOOP;

    -- 2. Все последовательности в схеме public → владелец afina_migrator
    FOR r IN
        SELECT sequencename FROM pg_sequences WHERE schemaname = 'public'
    LOOP
        EXECUTE 'ALTER SEQUENCE public.' || quote_ident(r.sequencename)
                || ' OWNER TO afina_migrator';
    END LOOP;
END
$$;

-- 3. Права на схему
GRANT ALL ON SCHEMA public TO afina_migrator;
GRANT USAGE ON SCHEMA public TO afina_app, afina_auditor;

-- 4. Права на существующие таблицы
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO afina_app;
GRANT SELECT                         ON ALL TABLES IN SCHEMA public TO afina_auditor;

-- 5. Права на последовательности
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO afina_app;
GRANT SELECT        ON ALL SEQUENCES IN SCHEMA public TO afina_auditor;

-- 6. Права по умолчанию для будущих объектов
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO afina_auditor;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT ON SEQUENCES TO afina_auditor;

-- 7. Особые права для flyway_schema_history — мигратор должен её читать и писать
GRANT ALL ON flyway_schema_history TO afina_migrator;
GRANT SELECT ON flyway_schema_history TO afina_app, afina_auditor;
