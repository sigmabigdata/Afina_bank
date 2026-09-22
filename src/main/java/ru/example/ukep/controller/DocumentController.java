package ru.example.ukep.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.service.DocumentService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final UserRepository userRepository;

    public DocumentController(DocumentService documentService, UserRepository userRepository) {
        this.documentService = documentService;
        this.userRepository = userRepository;
    }

    private User current(UserDetails p) {
        return userRepository.findByEmail(p.getUsername()).orElseThrow();
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @AuthenticationPrincipal UserDetails p) throws IOException {
        documentService.upload(file, current(p));
        return "redirect:/dashboard";
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<Resource> view(@PathVariable Long id,
                                         @AuthenticationPrincipal UserDetails p) throws IOException {
        Document doc = documentService.getOwned(id, current(p));
        Resource r = documentService.loadAsResource(doc);
        MediaType mt = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" +
                        URLEncoder.encode(doc.getOriginalName(), StandardCharsets.UTF_8))
                .body(r);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @AuthenticationPrincipal UserDetails p) throws IOException {
        Document doc = documentService.getOwned(id, current(p));
        Resource r = documentService.loadAsResource(doc);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(doc.getOriginalName(), StandardCharsets.UTF_8))
                .body(r);
    }

    /**
     * Скачать подпись .sig (отсоединённая CAdES-BES в DER).
     */
    @GetMapping("/{id}/signature/download")
    public ResponseEntity<byte[]> downloadSignature(@PathVariable Long id,
                                                    @AuthenticationPrincipal UserDetails p) {
        Document doc = documentService.getOwned(id, current(p));
        if (!doc.isSigned() || doc.getSignatureBase64() == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] sigBytes = Base64.getDecoder().decode(
                doc.getSignatureBase64().replaceAll("\\s+", ""));

        String baseName = doc.getOriginalName();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(baseName + ".sig", StandardCharsets.UTF_8))
                .body(sigBytes);
    }

    /**
     * Скачать ZIP: документ + подпись.
     */
    @GetMapping("/{id}/download-signed")
    public ResponseEntity<byte[]> downloadSignedZip(@PathVariable Long id,
                                                    @AuthenticationPrincipal UserDetails p) throws IOException {
        Document doc = documentService.getOwned(id, current(p));
        if (!doc.isSigned() || doc.getSignatureBase64() == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] docBytes = java.nio.file.Files.readAllBytes(documentService.getPath(doc));
        byte[] sigBytes = Base64.getDecoder().decode(
                doc.getSignatureBase64().replaceAll("\\s+", ""));

        String baseName = doc.getOriginalName();
        int dot = baseName.lastIndexOf('.');
        String sigName = (dot > 0 ? baseName.substring(0, dot) : baseName) + ".sig";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(doc.getOriginalName()));
            zip.write(docBytes);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(sigName));
            zip.write(sigBytes);
            zip.closeEntry();
        }

        String zipName = (dot > 0 ? baseName.substring(0, dot) : baseName) + "_signed.zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(zipName, StandardCharsets.UTF_8))
                .body(baos.toByteArray());
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UserDetails p) throws IOException {
        documentService.delete(id, current(p));
        return "redirect:/dashboard";
    }
}