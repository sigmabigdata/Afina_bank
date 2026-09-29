-- ============================================================
-- Афина · Настройка pgaudit для ролей
-- ============================================================
-- Уровни аудита:
--   afina_app      — пишет операции записи (INSERT/UPDATE/DELETE) и DDL
--   afina_migrator — только DDL (создание таблиц миграциями)
--   afina_auditor  — ничего не пишет, только читает
-- ============================================================

ALTER ROLE afina_app      SET pgaudit.log = 'write, ddl';
ALTER ROLE afina_migrator SET pgaudit.log = 'ddl';
ALTER ROLE afina_auditor  SET pgaudit.log = 'none';

-- Логировать объекты, попадающие в аудит
ALTER ROLE afina_app      SET pgaudit.log_catalog = 'off';
ALTER ROLE afina_app      SET pgaudit.log_parameter = 'off';
ALTER ROLE afina_app      SET pgaudit.log_relation = 'on';
ALTER ROLE afina_app      SET pgaudit.log_statement_once = 'off';
