package ru.example.ukep.service;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.dto.RegistrationForm;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    // === UserDetailsService ===

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByEmail(username.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден: " + username));

        return org.springframework.security.core.userdetails.User
                .withUsername(u.getEmail())
                .password(u.getPasswordHash())
                .disabled(!u.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority(u.getRole().name())))
                .build();
    }

    // === Регистрация ===

    @Transactional
    public User register(RegistrationForm form, String baseUrl) {
        if (!form.getPassword().equals(form.getPasswordConfirm())) {
            throw new IllegalArgumentException("Пароли не совпадают");
        }
        if (userRepository.existsByEmail(form.getEmail().toLowerCase())) {
            throw new IllegalArgumentException("Пользователь с таким email уже существует");
        }

        User user = new User();
        user.setEmail(form.getEmail().toLowerCase());
        user.setFullName(form.getFullName());
        user.setPhone(form.getPhone());
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setRole(Role.ROLE_USER);
        user.setEnabled(false);
        user.setConfirmationToken(UUID.randomUUID().toString());
        user.setConfirmationExpires(Instant.now().plus(24, ChronoUnit.HOURS));

        user = userRepository.save(user);

        String url = baseUrl + "/confirm?token=" + user.getConfirmationToken();
        emailService.sendConfirmation(user.getEmail(), url);

        return user;
    }

    @Transactional
    public boolean confirm(String token) {
        Optional<User> opt = userRepository.findByConfirmationToken(token);
        if (opt.isEmpty()) return false;
        User u = opt.get();
        if (u.getConfirmationExpires() == null || u.getConfirmationExpires().isBefore(Instant.now())) {
            return false;
        }
        u.setEnabled(true);
        u.setConfirmationToken(null);
        u.setConfirmationExpires(null);
        userRepository.save(u);
        return true;
    }

    @Transactional
    public void createAdminIfMissing(String email, String rawPassword, String fullName) {
        Optional<User> existing = userRepository.findByEmail(email.toLowerCase());
        if (existing.isPresent()) return;
        User u = new User();
        u.setEmail(email.toLowerCase());
        u.setFullName(fullName);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(Role.ROLE_ADMIN);
        u.setEnabled(true);
        userRepository.save(u);
    }
}
