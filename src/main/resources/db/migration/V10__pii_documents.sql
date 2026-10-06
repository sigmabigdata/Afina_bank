-- ============================================================
-- PII-шифрование: Document.originalName, signer subject
-- ============================================================
-- Добавляем *_enc колонки. Старые оставляем nullable (не удаляем),
-- чтобы MigrationRunner мог перешифровать существующие данные.
-- После успешной миграции их можно дропнуть отдельной V11.
-- ============================================================

-- documents
ALTER TABLE documents ALTER COLUMN original_name DROP NOT NULL;
ALTER TABLE documents ADD COLUMN original_name_enc TEXT;
ALTER TABLE documents ADD COLUMN signer_subject_enc VARCHAR(1000);

-- document_signatures
ALTER TABLE document_signatures ADD COLUMN signer_subject_enc VARCHAR(1000);

COMMENT ON COLUMN documents.original_name_enc IS 'Имя файла, AES-256-GCM';
COMMENT ON COLUMN documents.signer_subject_enc IS 'CN подписанта, AES-256-GCM';
COMMENT ON COLUMN document_signatures.signer_subject_enc IS 'CN подписанта, AES-256-GCM';

-- Права для аудитора не нужны на *_enc — только через приложение
