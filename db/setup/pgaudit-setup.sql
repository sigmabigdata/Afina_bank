-- ============================================================
-- Афина · Настройка pgaudit для ролей
-- ============================================================
ALTER ROLE afina_app      SET pgaudit.log = 'write, ddl';
ALTER ROLE afina_migrator SET pgaudit.log = 'ddl';
ALTER ROLE afina_auditor  SET pgaudit.log = 'none';

ALTER ROLE afina_app      SET pgaudit.log_catalog = 'off';
ALTER ROLE afina_app      SET pgaudit.log_parameter = 'off';
ALTER ROLE afina_app      SET pgaudit.log_relation = 'on';
ALTER ROLE afina_app      SET pgaudit.log_statement_once = 'off';
