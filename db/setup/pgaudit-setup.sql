-- ============================================================
-- Афина · Настройка pgAudit (одноразовая DBA-операция)
-- ============================================================
-- ВЫПОЛНЯЕТСЯ ПОД СУПЕРЮЗЕРОМ (app_user).
-- НЕ ВХОДИТ в Flyway-миграции, потому что:
--   1. CREATE EXTENSION pgaudit требует superuser
--   2. ALTER ROLE требует CREATEROLE
-- Эти права не должны быть у мигратора приложения.
--
-- Запуск: ./setup-db.sh
-- ============================================================

-- 1. Устанавливаем расширение pgaudit
CREATE EXTENSION IF NOT EXISTS pgaudit;

-- 2. Настраиваем аудит для роли приложения
--    write — INSERT/UPDATE/DELETE/TRUNCATE
--    ddl   — CREATE/ALTER/DROP
--    role  — GRANT/REVOKE/CREATE ROLE
--    misc  — прочие опасные операции (COPY FROM PROGRAM и т.п.)
ALTER ROLE afina_app SET pgaudit.log = 'write, ddl, role, misc';
ALTER ROLE afina_app SET pgaudit.log_parameter = on;
ALTER ROLE afina_app SET pgaudit.log_statement_once = off;

-- 3. Настраиваем аудит для мигратора
--    ddl   — CREATE/ALTER/DROP (все миграции)
--    role  — если миграция меняет роли (обычно нет)
ALTER ROLE afina_migrator SET pgaudit.log = 'ddl, role';
ALTER ROLE afina_migrator SET pgaudit.log_parameter = off;

-- 4. Аудитор: логируем чтение
ALTER ROLE afina_auditor SET pgaudit.log = 'read';

-- 5. Проверка, что настройки применились
DO $$
DECLARE
    r RECORD;
BEGIN
    RAISE NOTICE '=== Настройки pgAudit ===';
    FOR r IN
        SELECT rolname, rolconfig
        FROM pg_roles
        WHERE rolname IN ('afina_app', 'afina_migrator', 'afina_auditor')
        ORDER BY rolname
    LOOP
        RAISE NOTICE '%: %', r.rolname, r.rolconfig;
    END LOOP;
END
$$;
