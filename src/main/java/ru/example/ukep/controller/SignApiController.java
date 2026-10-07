package ru.example.ukep.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.dto.SignRequest;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureVerifier;

import java.util.Map;

@RestController
@RequestMapping("/api/sign")
public class SignApiController {

    private final DocumentService documentService;
    private final SignatureVerifier signatureVerifier;
    private final AuditService audit;
    private final UserRepository userRepository;
    private final PiiEncryptor pii;
    private final DocumentRepository documentRepository;

    public SignApiController(DocumentService documentService,
                             SignatureVerifier signatureVerifier,
                             AuditService audit,
                             UserRepository userRepository,
                             DocumentRepository documentRepository,
                            PiiEncryptor pii) {
        this.documentService = documentService;
        this.signatureVerifier = signatureVerifier;
        this.audit = audit;
        this.userRepository = userRepository;
        this.pii = pii;
        this.documentRepository = documentRepository;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmailHash(pii.hash(p.getUsername())).orElseThrow();
    }

    /** Пользователь подписывает свой документ. */
    @PostMapping("/accept")
    public ResponseEntity<?> acceptUser(@RequestBody SignRequest req,
                                        @AuthenticationPrincipal UserDetails p) {
        User user = current(p);
        Document doc = documentService.getOwned(req.getDocumentId(), user);
        return doAccept(doc, user, req.getSignatureBase64());
    }

    /** Админ подписывает документ любого клиента. */
    @PostMapping("/admin/accept")
    public ResponseEntity<?> acceptAdmin(@RequestBody SignRequest req,
                                         @AuthenticationPrincipal UserDetails p) {
        User admin = current(p);
        if (admin.getRole() != ru.example.ukep.entity.Role.ROLE_ADMIN) {
            return ResponseEntity.status(403).body(Map.of("error", "Только для администратора"));
        }
        Document doc = documentRepository.findByIdWithOwner(req.getDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        return doAccept(doc, doc.getOwner(), req.getSignatureBase64());
    }

    private ResponseEntity<?> doAccept(Document doc, User owner, String sig) {
        String ownerEmail = safeOwnerEmail(owner);
        try {
            Map<String, Object> result = signatureVerifier.verifyDetached(
                    documentService.getBytes(doc), sig);
            String subject = String.valueOf(result.getOrDefault("signerSubject", ""));
            String serial = String.valueOf(result.getOrDefault("signerSerial", ""));
            documentService.addSignature(doc.getId(), owner, sig, subject, serial);
            audit.signSuccess(ownerEmail, doc.getId(), subject);
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "signersCount", result.get("signersCount"),
                    "signersInfo", result.get("signersInfo")));
        } catch (IllegalArgumentException e) {
            audit.signFail(ownerEmail, doc.getId(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            audit.signFail(ownerEmail, doc.getId(), e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка проверки подписи: " + e.getMessage()));
        }
    }

    /** Безопасно получить email владельца (не падать при LazyInit). */
    private String safeOwnerEmail(User owner) {
        try {
            return owner.getEmail();
        } catch (Exception e) {
            return "user#" + (owner != null ? owner.getId() : "?");
        }
    }
}
