package ru.example.ukep.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.dto.SignRequest;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
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

    @Value("${app.crl-path:./kontur-q-2025.crl}")
    private String crlPath;

    public SignApiController(DocumentService documentService,
                             SignatureService signatureService,
                             UserRepository userRepository) {
        this.documentService = documentService;
        this.signatureService = signatureService;
        this.userRepository = userRepository;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmail(p.getUsername()).orElseThrow();
    }

    @PostMapping("/accept")
    public ResponseEntity<?> accept(@RequestBody SignRequest req,
                                    @AuthenticationPrincipal UserDetails p) {
        try {
            User user = current(p);
            Document doc = documentService.getOwned(req.getDocumentId(), user);

            Map<String, Object> result = signatureService.verifyDetached(
                    documentService.getPath(doc),
                    req.getSignatureBase64(),
                    Path.of(crlPath)
            );

            documentService.saveSignature(doc.getId(), user, req.getSignatureBase64());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка проверки подписи: " + e.getMessage()));
        }
    }
}
