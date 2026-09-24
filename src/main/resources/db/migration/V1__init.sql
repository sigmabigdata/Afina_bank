-- ============================================================
-- Афина · Инициализация схемы (V1)
-- ============================================================

CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    full_name             VARCHAR(255) NOT NULL,
    phone                 VARCHAR(50),
    role                  VARCHAR(30)  NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    login_token           VARCHAR(128),
    login_token_expires   TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email           ON users (LOWER(email));
CREATE INDEX idx_users_login_token     ON users (login_token);
CREATE INDEX idx_users_role            ON users (role);

CREATE TABLE documents (
    id                    BIGSERIAL PRIMARY KEY,
    original_name         VARCHAR(500) NOT NULL,
    stored_name           VARCHAR(500) NOT NULL,
    content_type          VARCHAR(200),
    size                  BIGINT       NOT NULL DEFAULT 0,
    file_sha256           VARCHAR(64),
    signature_base64      TEXT,
    signed                BOOLEAN      NOT NULL DEFAULT FALSE,
    signed_at             TIMESTAMPTZ,
    signer_subject        VARCHAR(500),
    signer_serial         VARCHAR(100),
    uploaded_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    owner_id              BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_documents_owner       ON documents (owner_id);
CREATE INDEX idx_documents_signed      ON documents (signed);
CREATE INDEX idx_documents_uploaded    ON documents (uploaded_at DESC);

-- Комментарии (для банковского аудита)
COMMENT ON TABLE users               IS 'Пользователи: клиенты и администраторы';
COMMENT ON COLUMN users.role         IS 'ROLE_USER | ROLE_ADMIN';
COMMENT ON COLUMN users.enabled      IS 'False — заблокирован';
COMMENT ON COLUMN users.login_token  IS 'Одноразовый токен для входа (magic-link)';
COMMENT ON TABLE documents           IS 'Загруженные документы';
COMMENT ON COLUMN documents.signed   IS 'True — подписан УКЭП';
COMMENT ON COLUMN documents.signer_subject IS 'CN подписанта (ФИО)';
COMMENT ON COLUMN documents.signature_base64 IS 'Отсоединённая подпись CAdES-BES, Base64';
