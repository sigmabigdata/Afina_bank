-- ============================================================
-- Удаление plaintext-колонок после успешной миграции PII
-- ============================================================
-- Данные полностью перенесены в *_enc/*_hash (V9, V10).
-- PiiMigrationRunner удалён — старые колонки больше не нужны.
-- ============================================================

ALTER TABLE users DROP COLUMN IF EXISTS email;
ALTER TABLE users DROP COLUMN IF EXISTS phone;

ALTER TABLE documents DROP COLUMN IF EXISTS original_name;
ALTER TABLE documents DROP COLUMN IF EXISTS signer_subject;

ALTER TABLE document_signatures DROP COLUMN IF EXISTS signer_subject;
