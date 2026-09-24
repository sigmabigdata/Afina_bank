package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    Optional<User> findByLoginToken(String loginToken);
    boolean existsByEmail(String email);
    List<User> findAllByOrderByCreatedAtDesc();

    long countByEnabledTrue();
    long countByEnabledFalse();

    /**
     * Поиск клиентов для админки.
     *
     * ВАЖНО: pattern (`%значение%`) формируется в сервисе/контроллере и передаётся
     * готовым. Не строим concat внутри JPQL и не делаем lower() над параметром —
     * Hibernate 6 биндит нетипизированный параметр как bytea, PostgreSQL не
     * может применить lower() к bytea.
     */
    @Query("select u from User u where " +
           "(:enabled is null or u.enabled = :enabled) " +
           "and (:role is null or u.role = :role) " +
           "and (:q is null " +
           "     or lower(u.email) like :q " +
           "     or lower(u.fullName) like :q " +
           "     or (u.phone is not null and lower(u.phone) like :q)" +
           ") " +
           "order by u.createdAt desc")
    List<User> searchForAdmin(@Param("enabled") Boolean enabled,
                              @Param("role") Role role,
                              @Param("q") String pattern);
}
