package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.User;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findAllByOwnerOrderByUploadedAtDesc(User owner);

    Optional<Document> findByIdAndOwner(Long id, User owner);

    @Query("select d from Document d join fetch d.owner order by d.uploadedAt desc")
    List<Document> findAllWithOwner();

    /**
     * Поиск документов для админки. Pattern (`%значение%`) приходит готовым из контроллера.
     */
    @Query("select d from Document d join fetch d.owner " +
           "where (:signed is null or d.signed = :signed) " +
           "and (:q is null " +
           "     or lower(d.originalName) like :q " +
           "     or lower(d.owner.email) like :q " +
           "     or lower(d.owner.fullName) like :q" +
           ") " +
           "order by d.uploadedAt desc")
    List<Document> searchForAdmin(@Param("signed") Boolean signed, @Param("q") String pattern);

    long countBySignedTrue();
    long countBySignedFalse();

    long countByOwner(User owner);
    long countByOwnerAndSignedTrue(User owner);
}
