package ru.example.ukep.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.dto.SignRequest;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureService;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/sign")
public class SignApiController {

    private final DocumentService documentService;
    private final SignatureService signatureService;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;

    @Value("${app.crl-path:./kontur-q-2025.crl}")
    private String crlPath;

    public SignApiController(DocumentService documentService,
                             SignatureService signatureService,
                             UserRepository userRepository,
                             DocumentRepository documentRepository) {
        this.documentService = documentService;
        this.signatureService = signatureService;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmail(p.getUsername()).orElseThrow();
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
        Document doc = documentRepository.findById(req.getDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        return doAccept(doc, doc.getOwner(), req.getSignatureBase64());
    }

    private ResponseEntity<?> doAccept(Document doc, User owner, String sig) {
        try {
            Map<String, Object> result = signatureService.verifyDetached(
                    documentService.getPath(doc), sig, Path.of(crlPath));
            String subject = String.valueOf(result.getOrDefault("signerSubject", ""));
            String serial = String.valueOf(result.getOrDefault("signerSerial", ""));
            documentService.saveSignature(doc.getId(), owner, sig, subject, serial);
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "signersCount", result.get("signersCount"),
                    "signersInfo", result.get("signersInfo")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка проверки подписи: " + e.getMessage()));
        }
    }
}
