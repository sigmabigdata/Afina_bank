package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.DocumentSignature;

import java.util.List;

public interface DocumentSignatureRepository extends JpaRepository<DocumentSignature, Long> {
    List<DocumentSignature> findAllByDocumentIdOrderBySignedAtAsc(Long documentId);
    long countByDocumentId(Long documentId);
}
