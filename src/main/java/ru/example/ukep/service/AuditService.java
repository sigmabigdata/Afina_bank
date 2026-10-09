package ru.example.ukep.service;

import org.springframework.stereotype.Service;

/**
 * Фасад для записи аудит-событий.
 *
 * Сама запись — в {@link AuditEventWriter} (@Transactional REQUIRES_NEW),
 * чтобы аудит-события коммитились даже при откате бизнес-транзакции.
 * Вынесено в отдельный бин, потому что Spring AOP не перехватывает
 * self-invocation, и Sonar (java:S2229) справедливо ругается.
 */
@Service
public class AuditService {

    private static final String OK = "SUCCESS";
    private static final String FAIL = "FAIL";
    private static final String WARN = "WARN";
    private static final String T_USER = "USER";
    private static final String T_DOC = "DOCUMENT";

    private final AuditEventWriter writer;

    public AuditService(AuditEventWriter writer) {
        this.writer = writer;
    }

    public void loginSuccess(String email, String role) {
        writer.write("LOGIN_SUCCESS", OK, email, role, null, null, null, null);
    }

    public void loginFail(String email, String reason) {
        writer.write("LOGIN_FAIL", FAIL, email, null, null, null, null, reason);
    }

    public void adminLoginSuccess(String cn) {
        writer.write("ADMIN_LOGIN_SUCCESS", OK, cn, "ROLE_ADMIN", null, null, null, null);
    }

    public void adminLoginFail(String cn, String reason) {
        writer.write("ADMIN_LOGIN_FAIL", FAIL, cn, "ROLE_ADMIN", null, null, null, reason);
    }

    public void userCreate(String actor, Long userId, String email) {
        writer.write("USER_CREATE", OK, actor, null, T_USER, String.valueOf(userId), email, null);
    }

    public void userUpdate(String actor, Long userId, String email) {
        writer.write("USER_UPDATE", OK, actor, null, T_USER, String.valueOf(userId), email, null);
    }

    public void userDelete(String actor, Long userId, String email) {
        writer.write("USER_DELETE", OK, actor, null, T_USER, String.valueOf(userId), email, null);
    }

    public void documentUpload(String actor, Long docId, String name) {
        writer.write("DOC_UPLOAD", OK, actor, null, T_DOC, String.valueOf(docId), name, null);
    }

    public void documentDelete(String actor, Long docId, String name, boolean signed) {
        writer.write("DOC_DELETE", OK, actor, null, T_DOC, String.valueOf(docId), name,
                signed ? "Подписанный документ" : null);
    }

    public void signSuccess(String actor, Long docId, String signerSubject) {
        writer.write("SIGN_SUCCESS", OK, actor, null, T_DOC, String.valueOf(docId), signerSubject, null);
    }

    public void signFail(String actor, Long docId, String reason) {
        writer.write("SIGN_FAIL", FAIL, actor, null, T_DOC, String.valueOf(docId), null, reason);
    }

    public void signatureDeleteAttempt(String actor, Long docId) {
        writer.write("SIGNATURE_DELETE_ATTEMPT", WARN, actor, null, T_DOC, String.valueOf(docId), null,
                "Попытка удаления подписи заблокирована");
    }

    public void rateLimit(String ip, String endpoint) {
        writer.write("RATE_LIMIT", WARN, null, null, "ENDPOINT", endpoint, null, "IP: " + ip);
    }

    public void backupCreate(String actor, String fileName) {
        writer.write("BACKUP_CREATE", OK, actor, null, "BACKUP", fileName, null, null);
    }

    public void backupRestore(String actor, String fileName) {
        writer.write("BACKUP_RESTORE", WARN, actor, null, "BACKUP", fileName, null, "Заменены все данные");
    }

    public void appRestart(String actor) {
        writer.write("APP_RESTART", WARN, actor, null, "SYSTEM", null, null, null);
    }

    public void settingsUpdate(String actor, String keys) {
        writer.write("SETTINGS_UPDATE", OK, actor, null, "SETTINGS", null, keys, null);
    }

    /**
     * Passthrough для нестандартных событий (EMAIL_SENT, KEYS_DOWNLOAD и т.п.).
     * Существует потому, что часть кода зовёт audit.event(...) напрямую.
     */
    public void event(String type, String result, String actorEmail, String actorRole,
                      String targetType, String targetId, String targetInfo, String details) {
        writer.write(type, result, actorEmail, actorRole, targetType, targetId, targetInfo, details);
    }
}
