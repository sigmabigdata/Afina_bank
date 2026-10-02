package ru.example.ukep.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentSignatureRepository;

import java.io.IOException;
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
    private final FileEncryptor encryptor;
    private final Path storageRoot;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentSignatureRepository signatureRepository,
                           FileEncryptor encryptor,
                           @Value("${app.storage-path}") String storagePath) throws IOException {
        this.documentRepository = documentRepository;
        this.signatureRepository = signatureRepository;
        this.encryptor = encryptor;
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
        byte[] original = file.getBytes();
        FileEncryptor.Encrypted enc = encryptor.encrypt(original);

        String baseName = UUID.randomUUID() + "_" + sanitize(file.getOriginalFilename());
        String storedName = baseName + ".enc";
        Path target = storageRoot.resolve(storedName);
        Files.write(target, enc.bytes(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        Document doc = new Document();
        doc.setOriginalName(file.getOriginalFilename());
        doc.setStoredName(storedName);
        doc.setContentType(file.getContentType());
        doc.setSize(original.length);
        doc.setFileSha256(sha256Bytes(original));
        doc.setEncryptionIv(enc.ivBase64());
        doc.setKeyVersion("v1");
        doc.setEncrypted(true);
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

    public byte[] getBytes(Document doc) throws IOException {
        Path p = storageRoot.resolve(doc.getStoredName());
        if (!Files.isRegularFile(p)) {
            throw new IOException("Файл не найден на диске: " + doc.getStoredName());
        }
        byte[] raw = Files.readAllBytes(p);
        if (!doc.isEncrypted()) return raw;
        return encryptor.decrypt(raw);
    }

    public Resource loadAsResource(Document doc) throws IOException {
        byte[] data = getBytes(doc);
        return new org.springframework.core.io.ByteArrayResource(data) {
            @Override public String getFilename() { return doc.getOriginalName(); }
        };
    }

    @Deprecated
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

    private String sha256Bytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
