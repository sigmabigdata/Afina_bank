package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findAllByOwnerOrderByUploadedAtDesc(User owner);
    Optional<Document> findByIdAndOwner(Long id, User owner);
}
