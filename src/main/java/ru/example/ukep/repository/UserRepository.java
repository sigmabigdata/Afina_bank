package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByConfirmationToken(String confirmationToken);
    boolean existsByEmail(String email);
}
