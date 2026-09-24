-- ============================================================
-- Афина · Владелец схемы — afina_migrator (V3)
-- ============================================================

DO $$
BEGIN
    GRANT ALL ON SCHEMA public TO afina_migrator;

    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT ON TABLES TO afina_auditor;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT ON SEQUENCES TO afina_auditor;
END
$$;
