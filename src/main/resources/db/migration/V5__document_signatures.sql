-- ============================================================
-- Неограниченное количество подписей на документ
-- ============================================================
-- Создаём отдельную таблицу: одна запись = одна подпись.
-- Существующие подписи из documents переносим сюда.

CREATE TABLE document_signatures (
    id                BIGSERIAL PRIMARY KEY,
    document_id       BIGINT       NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    signature_base64  TEXT         NOT NULL,
    signed_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    signer_subject    VARCHAR(500),
    signer_serial     VARCHAR(100),
    signer_user_id    BIGINT       REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_doc_sig_document ON document_signatures (document_id);
CREATE INDEX idx_doc_sig_signed_at ON document_signatures (signed_at DESC);

COMMENT ON TABLE  document_signatures IS 'Подписи документа (неограниченное количество)';
COMMENT ON COLUMN document_signatures.signer_subject IS 'CN подписанта (ФИО)';
COMMENT ON COLUMN document_signatures.signature_base64 IS 'Отсоединённая подпись CAdES-BES, Base64';

-- Переносим существующие подписи из documents (если были)
INSERT INTO document_signatures
    (document_id, signature_base64, signed_at, signer_subject, signer_serial, signer_user_id)
SELECT id, signature_base64, COALESCE(signed_at, uploaded_at),
       signer_subject, signer_serial, owner_id
FROM documents
WHERE signed = TRUE AND signature_base64 IS NOT NULL;
