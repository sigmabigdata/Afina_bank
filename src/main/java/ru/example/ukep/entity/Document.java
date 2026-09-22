package ru.example.ukep.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false)
    private String storedName;

    private String contentType;

    private long size;

    private String fileSha256;

    @Column(columnDefinition = "TEXT")
    private String signatureBase64;

    private boolean signed = false;

    private Instant signedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();

    // --- getters ---
    public Long getId() { return id; }
    public String getOriginalName() { return originalName; }
    public String getStoredName() { return storedName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public String getFileSha256() { return fileSha256; }
    public String getSignatureBase64() { return signatureBase64; }
    public boolean isSigned() { return signed; }
    public Instant getSignedAt() { return signedAt; }
    public User getOwner() { return owner; }
    public Instant getUploadedAt() { return uploadedAt; }

    // --- setters ---
    public void setId(Long id) { this.id = id; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setSize(long size) { this.size = size; }
    public void setFileSha256(String fileSha256) { this.fileSha256 = fileSha256; }
    public void setSignatureBase64(String signatureBase64) { this.signatureBase64 = signatureBase64; }
    public void setSigned(boolean signed) { this.signed = signed; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }
    public void setOwner(User owner) { this.owner = owner; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
