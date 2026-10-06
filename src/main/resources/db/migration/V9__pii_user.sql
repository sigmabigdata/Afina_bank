-- ============================================================
-- PII-шифрование: User.email, User.phone
-- ============================================================
-- Старые plaintext-колонки НЕ удаляем. Просто снимаем NOT NULL.
-- PiiMigrationRunner при старте зашифрует их в *_enc/*_hash.
-- Отдельной миграцией позже (V11+) удалим старые колонки.
-- ============================================================

ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

ALTER TABLE users ADD COLUMN email_enc  TEXT;
ALTER TABLE users ADD COLUMN email_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN phone_enc  TEXT;
ALTER TABLE users ADD COLUMN phone_hash VARCHAR(64);

COMMENT ON COLUMN users.email_enc  IS 'Email, зашифрованный AES-256-GCM (base64)';
COMMENT ON COLUMN users.email_hash IS 'SHA-256 от lower(email) — для unique и exact-поиска';
COMMENT ON COLUMN users.phone_enc  IS 'Телефон, зашифрованный AES-256-GCM';
COMMENT ON COLUMN users.phone_hash IS 'SHA-256 от digits(phone) — для exact-поиска';

-- Уникальность через hash (nullable — старые записи до миграции)
CREATE UNIQUE INDEX idx_users_email_hash ON users (email_hash) WHERE email_hash IS NOT NULL;
CREATE INDEX idx_users_phone_hash ON users (phone_hash) WHERE phone_hash IS NOT NULL;

-- Дроп старого unique-индекса на email (если есть)
DROP INDEX IF EXISTS users_email_key;

GRANT SELECT (email_enc, email_hash, phone_enc, phone_hash) ON users TO afina_auditor;
