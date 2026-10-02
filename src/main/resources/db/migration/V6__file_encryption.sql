CREATE TABLE file_keys (
    id          BIGSERIAL PRIMARY KEY,
    version     VARCHAR(20)  NOT NULL UNIQUE,
    algorithm   VARCHAR(50)  NOT NULL DEFAULT 'AES-256-GCM',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    comment     VARCHAR(500)
);
COMMENT ON TABLE file_keys IS 'Версии ключей шифрования файлов';
INSERT INTO file_keys (version, comment) VALUES ('v1', 'Initial AES-256-GCM key');

ALTER TABLE documents ADD COLUMN encryption_iv VARCHAR(32);
ALTER TABLE documents ADD COLUMN key_version   VARCHAR(20) REFERENCES file_keys(version);
ALTER TABLE documents ADD COLUMN encrypted     BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN documents.encryption_iv IS 'Base64 IV для AES-GCM (12 байт)';
COMMENT ON COLUMN documents.key_version   IS 'Версия ключа шифрования';
COMMENT ON COLUMN documents.encrypted     IS 'True — файл зашифрован на диске';

GRANT SELECT ON file_keys TO afina_app, afina_auditor;
GRANT INSERT, UPDATE, DELETE ON file_keys TO afina_app;
GRANT USAGE, SELECT ON SEQUENCE file_keys_id_seq TO afina_app;
