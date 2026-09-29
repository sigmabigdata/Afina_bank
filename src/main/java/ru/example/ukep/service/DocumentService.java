package ru.example.ukep.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentSignatureRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentSignatureRepository signatureRepository;
    private final Path storageRoot;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentSignatureRepository signatureRepository,
                           @Value("${app.storage-path}") String storagePath) throws IOException {
        this.documentRepository = documentRepository;
        this.signatureRepository = signatureRepository;
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Files.createDirectories(storageRoot);
    }

    public List<Document> listForUser(User owner) {
        return documentRepository.findAllByOwnerOrderByUploadedAtDesc(owner);
    }

    public Document getOwned(Long id, User owner) {
        return documentRepository.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
    }

    /** Для админа: получить документ по id без проверки владельца. */
    public Document getById(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
    }

    /** Для админа: удалить документ без проверки владельца. */
    @Transactional
    public void deleteAsAdmin(Long id) throws IOException {
        Document doc = getById(id);
        Files.deleteIfExists(storageRoot.resolve(doc.getStoredName()));
        documentRepository.delete(doc);
    }

    @Transactional
    public Document upload(MultipartFile file, User owner) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Файл пустой");
        String stored = UUID.randomUUID() + "_" + sanitize(file.getOriginalFilename());
        Path target = storageRoot.resolve(stored);
        try (InputStream is = file.getInputStream()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Document doc = new Document();
        doc.setOriginalName(file.getOriginalFilename());
        doc.setStoredName(stored);
        doc.setContentType(file.getContentType());
        doc.setSize(file.getSize());
        doc.setFileSha256(sha256(target));
        doc.setOwner(owner);
        doc.setUploadedAt(Instant.now());
        return documentRepository.save(doc);
    }

    @Transactional
    public void delete(Long id, User owner) throws IOException {
        Document doc = getOwned(id, owner);
        Files.deleteIfExists(storageRoot.resolve(doc.getStoredName()));
        documentRepository.delete(doc);
    }

    public Resource loadAsResource(Document doc) throws IOException {
        Path p = storageRoot.resolve(doc.getStoredName());
        return new UrlResource(p.toUri());
    }

    public Path getPath(Document doc) { return storageRoot.resolve(doc.getStoredName()); }

    /** Добавить новую подпись документу (неограниченное количество). */
    @Transactional
    public DocumentSignature addSignature(Long docId, User signer,
                                          String signatureBase64,
                                          String signerSubject, String signerSerial) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));

        DocumentSignature sig = new DocumentSignature();
        sig.setDocument(doc);
        sig.setSignatureBase64(signatureBase64);
        sig.setSignedAt(Instant.now());
        sig.setSignerSubject(signerSubject);
        sig.setSignerSerial(signerSerial);
        sig.setSignerUser(signer);
        signatureRepository.save(sig);

        // Флаг "signed" на документе = есть ли хотя бы одна подпись
        if (!doc.isSigned()) {
            doc.setSigned(true);
            doc.setSignedAt(sig.getSignedAt());
            doc.setSignerSubject(signerSubject);
            doc.setSignerSerial(signerSerial);
            documentRepository.save(doc);
        }
        return sig;
    }

    /** Получить подпись по id. */
    public DocumentSignature getSignature(Long signatureId) {
        return signatureRepository.findById(signatureId)
                .orElseThrow(() -> new IllegalArgumentException("Подпись не найдена"));
    }

    /** Все подписи документа. */
    public List<DocumentSignature> listSignatures(Long documentId) {
        return signatureRepository.findAllByDocumentIdOrderBySignedAtAsc(documentId);
    }

    /** Удалить подпись. */
    @Transactional
    public void deleteSignature(Long signatureId) {
        DocumentSignature sig = getSignature(signatureId);
        Document doc = sig.getDocument();
        signatureRepository.delete(sig);

        // Если больше нет подписей — сбросить флаг
        if (signatureRepository.countByDocumentId(doc.getId()) == 0) {
            doc.setSigned(false);
            doc.setSignedAt(null);
            doc.setSignerSubject(null);
            doc.setSignerSerial(null);
            documentRepository.save(doc);
        }
    }

    /** Legacy-метод для совместимости. */
    @Deprecated
    @Transactional
    public void saveSignature(Long docId, User owner, String signatureBase64,
                              String signerSubject, String signerSerial) {
        addSignature(docId, owner, signatureBase64, signerSubject, signerSerial);
    }

    private String sanitize(String n) { return n == null ? "file" : n.replaceAll("[^a-zA-Z0-9._-]", "_"); }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) { throw new IOException(e); }
    }
}
