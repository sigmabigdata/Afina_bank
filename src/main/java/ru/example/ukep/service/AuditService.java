package ru.example.ukep.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.example.ukep.entity.AuditEvent;
import ru.example.ukep.repository.AuditEventRepository;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repo;

    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
    }

    public void loginSuccess(String email, String role) {
        event("LOGIN_SUCCESS", "SUCCESS", email, role, null, null, null, null);
    }

    public void loginFail(String email, String reason) {
        event("LOGIN_FAIL", "FAIL", email, null, null, null, null, reason);
    }

    public void adminLoginSuccess(String cn) {
        event("ADMIN_LOGIN_SUCCESS", "SUCCESS", cn, "ROLE_ADMIN", null, null, null, null);
    }

    public void adminLoginFail(String cn, String reason) {
        event("ADMIN_LOGIN_FAIL", "FAIL", cn, "ROLE_ADMIN", null, null, null, reason);
    }

    public void userCreate(String actor, Long userId, String email) {
        event("USER_CREATE", "SUCCESS", actor, null, "USER", String.valueOf(userId), email, null);
    }

    public void userUpdate(String actor, Long userId, String email) {
        event("USER_UPDATE", "SUCCESS", actor, null, "USER", String.valueOf(userId), email, null);
    }

    public void userDelete(String actor, Long userId, String email) {
        event("USER_DELETE", "SUCCESS", actor, null, "USER", String.valueOf(userId), email, null);
    }

    public void documentUpload(String actor, Long docId, String name) {
        event("DOC_UPLOAD", "SUCCESS", actor, null, "DOCUMENT", String.valueOf(docId), name, null);
    }

    public void documentDelete(String actor, Long docId, String name, boolean signed) {
        event("DOC_DELETE", "SUCCESS", actor, null, "DOCUMENT", String.valueOf(docId), name,
                signed ? "Подписанный документ" : null);
    }

    public void signSuccess(String actor, Long docId, String signerSubject) {
        event("SIGN_SUCCESS", "SUCCESS", actor, null, "DOCUMENT", String.valueOf(docId), signerSubject, null);
    }

    public void signFail(String actor, Long docId, String reason) {
        event("SIGN_FAIL", "FAIL", actor, null, "DOCUMENT", String.valueOf(docId), null, reason);
    }

    public void signatureDeleteAttempt(String actor, Long docId) {
        event("SIGNATURE_DELETE_ATTEMPT", "WARN", actor, null, "DOCUMENT", String.valueOf(docId), null,
                "Попытка удаления подписи заблокирована");
    }

    public void rateLimit(String ip, String endpoint) {
        event("RATE_LIMIT", "WARN", null, null, "ENDPOINT", endpoint, null, "IP: " + ip);
    }

    public void backupCreate(String actor, String fileName) {
        event("BACKUP_CREATE", "SUCCESS", actor, null, "BACKUP", fileName, null, null);
    }

    public void backupRestore(String actor, String fileName) {
        event("BACKUP_RESTORE", "WARN", actor, null, "BACKUP", fileName, null, "Заменены все данные");
    }

    public void appRestart(String actor) {
        event("APP_RESTART", "WARN", actor, null, "SYSTEM", null, null, null);
    }

    public void settingsUpdate(String actor, String keys) {
        event("SETTINGS_UPDATE", "SUCCESS", actor, null, "SETTINGS", null, keys, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void event(String type, String result, String actorEmail, String actorRole,
                      String targetType, String targetId, String targetInfo, String details) {
        try {
            AuditEvent e = new AuditEvent();
            e.setEventType(type);
            e.setResult(result);
            e.setActorEmail(actorEmail);
            e.setActorRole(actorRole);
            e.setTargetType(targetType);
            e.setTargetId(targetId);
            e.setTargetInfo(truncate(targetInfo, 500));
            e.setDetails(details);

            HttpServletRequest req = currentRequest();
            if (req != null) {
                e.setActorIp(clientIp(req));
                e.setUserAgent(truncate(req.getHeader("User-Agent"), 500));
            }

            repo.save(e);
            log.debug("audit: {} {}", type, result);
        } catch (Exception ex) {
            log.warn("audit failed: {} — {}", type, ex.getMessage());
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return req.getRemoteAddr();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
