package ru.example.ukep.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.example.ukep.security.PiiEncryptor;

import java.util.List;
import java.util.Map;

/**
 * Одноразовая миграция plaintext → encrypted для PII-полей.
 *
 * Читает СТАРЫЕ колонки (email, phone, original_name, signer_subject)
 * через JdbcTemplate (в обход JPA @Convert), шифрует и пишет в *_enc/*_hash.
 *
 * Идемпотентно: пропускает уже мигрированные (WHERE *_enc IS NULL).
 */
@Component
@Order(0)
public class PiiMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PiiMigrationRunner.class);

    private final JdbcTemplate jdbc;
    private final PiiEncryptor pii;

    public PiiMigrationRunner(JdbcTemplate jdbc, PiiEncryptor pii) {
        this.jdbc = jdbc;
        this.pii = pii;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== PiiMigrationRunner старт ===");
        try {
            int users = migrateUsers();
            int docs = migrateDocuments();
            int sigs = migrateSignatures();
            log.info("=== PiiMigrationRunner: users={}, documents={}, signatures={} ===",
                    users, docs, sigs);
        } catch (Exception e) {
            log.error("PiiMigrationRunner: КРИТИЧЕСКАЯ ОШИБКА", e);
            // Не бросаем — приложение должно стартовать, чтобы админ мог
            // разобраться. Но логируем громко.
        }
    }

    private int migrateUsers() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, email, phone FROM users " +
                "WHERE email_enc IS NULL AND email IS NOT NULL");
        if (rows.isEmpty()) {
            log.info("users: нечего мигрировать");
            return 0;
        }
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String email = (String) row.get("email");
            String phone = (String) row.get("phone");

            String emailEnc = pii.encrypt(email);
            String emailHash = pii.hash(email);
            String phoneEnc = phone != null && !phone.isBlank() ? pii.encrypt(phone) : null;
            String phoneHash = phone != null && !phone.isBlank() ? pii.hashPhone(phone) : null;

            jdbc.update(
                    "UPDATE users SET email_enc = ?, email_hash = ?, " +
                    "phone_enc = ?, phone_hash = ? WHERE id = ?",
                    emailEnc, emailHash, phoneEnc, phoneHash, id);
        }
        log.info("users: зашифровано {}", rows.size());
        return rows.size();
    }

    private int migrateDocuments() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, original_name, signer_subject FROM documents " +
                "WHERE original_name_enc IS NULL AND original_name IS NOT NULL");
        if (rows.isEmpty()) {
            log.info("documents: нечего мигрировать");
            return 0;
        }
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String originalName = (String) row.get("original_name");
            String signerSubject = (String) row.get("signer_subject");

            jdbc.update(
                    "UPDATE documents SET original_name_enc = ?, signer_subject_enc = ? " +
                    "WHERE id = ?",
                    pii.encrypt(originalName),
                    signerSubject != null ? pii.encrypt(signerSubject) : null,
                    id);
        }
        log.info("documents: зашифровано {}", rows.size());
        return rows.size();
    }

    private int migrateSignatures() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, signer_subject FROM document_signatures " +
                "WHERE signer_subject_enc IS NULL AND signer_subject IS NOT NULL");
        if (rows.isEmpty()) {
            log.info("signatures: нечего мигрировать");
            return 0;
        }
        for (Map<String, Object> row : rows) {
            Long id = ((Long) row.get("id"));
            String signerSubject = (String) row.get("signer_subject");
            jdbc.update(
                    "UPDATE document_signatures SET signer_subject_enc = ? WHERE id = ?",
                    pii.encrypt(signerSubject), id);
        }
        log.info("signatures: зашифровано {}", rows.size());
        return rows.size();
    }
}
