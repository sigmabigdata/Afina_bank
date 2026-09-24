-- ============================================================
-- Афина · Права доступа (V2)
-- ============================================================
-- Модель разделения привилегий:
--   afina_migrator — владелец схемы и таблиц, DDL (миграции Flyway)
--   afina_app      — только DML (SELECT/INSERT/UPDATE/DELETE)
--   afina_auditor  — только чтение (SELECT)
-- ============================================================

-- 1. Подключение к БД и использование схемы
GRANT CONNECT ON DATABASE afina_db TO afina_migrator, afina_app, afina_auditor;
GRANT USAGE   ON SCHEMA  public    TO afina_migrator, afina_app, afina_auditor;

-- 2. Migrator может управлять схемой
GRANT CREATE ON SCHEMA public TO afina_migrator;

-- 3. Права на существующие таблицы
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO afina_app;
GRANT SELECT                          ON ALL TABLES IN SCHEMA public TO afina_auditor;

-- 4. Права на последовательности (для INSERT — серийные ID)
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO afina_app;
GRANT SELECT         ON ALL SEQUENCES IN SCHEMA public TO afina_auditor;

-- 5. Права по умолчанию для БУДУЩИХ таблиц (когда migrator создаёт их)
--    Это важно: без этого каждая новая таблица потребует ручного GRANT.
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO afina_auditor;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
    GRANT SELECT ON SEQUENCES TO afina_auditor;

-- 6. Отзываем возможность подключения ко всем БД у public (безопасность)
REVOKE ALL ON DATABASE afina_db FROM PUBLIC;
GRANT CONNECT ON DATABASE afina_db TO afina_migrator, afina_app, afina_auditor;

COMMENT ON SCHEMA public IS 'Основная схема приложения Афина';
