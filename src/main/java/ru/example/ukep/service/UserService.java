package ru.example.ukep.service;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserService.class);

    public static final Duration LOGIN_TOKEN_TTL = Duration.ofHours(10);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // ============ Spring Security ============

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByEmail(username.toLowerCase().trim())
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден: " + username));
        return org.springframework.security.core.userdetails.User
                .withUsername(u.getEmail())
                .password("{noop}magic")
                .disabled(!u.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority(u.getRole().name())))
                .build();
    }

    // ============ Magic-link для клиентов ============

    @Transactional
    public String generateLoginLink(String email, String baseUrl) {
        Optional<User> opt = userRepository.findByEmail(email.toLowerCase().trim());
        if (opt.isEmpty()) return null;
        User u = opt.get();
        if (!u.isEnabled()) return null;

        String token = UUID.randomUUID().toString().replace("-", "");
        u.setLoginToken(token);
        u.setLoginTokenExpires(Instant.now().plus(LOGIN_TOKEN_TTL));
        userRepository.save(u);

        return baseUrl + "/login/confirm?token=" + token;
    }

    @Transactional
    public User consumeLoginToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("consumeLoginToken: token is null/blank");
            return null;
        }
        Optional<User> opt = userRepository.findByLoginToken(token);
        if (opt.isEmpty()) {
            log.warn("consumeLoginToken: no user with token (len={})", token.length());
            return null;
        }
        User u = opt.get();
        if (u.getLoginTokenExpires() == null || u.getLoginTokenExpires().isBefore(Instant.now())) {
            log.warn("consumeLoginToken: token expired for {} (expires={})",
                    u.getEmail(), u.getLoginTokenExpires());
            return null;
        }
        u.setLoginToken(null);
        u.setLoginTokenExpires(null);
        u.setLastLoginAt(Instant.now());
        log.info("consumeLoginToken: success for {}", u.getEmail());
        return userRepository.save(u);
    }

    // ============ Админ по сертификату ============

    @Transactional
    public User upsertAdminByCert(String cn, String snils) {
        String syntheticEmail = buildAdminEmail(cn, snils);
        User u = userRepository.findByEmail(syntheticEmail).orElseGet(User::new);
        u.setEmail(syntheticEmail);
        u.setFullName(cn);
        u.setRole(Role.ROLE_ADMIN);
        u.setEnabled(true);
        u.setLoginToken(null);
        u.setLoginTokenExpires(null);
        return userRepository.save(u);
    }

    public static String buildAdminEmail(String cn, String snils) {
        String safeCn = cn == null ? "admin" : cn.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.|\\.$", "");
        return safeCn + "+" + snils + "@ukep.local";
    }

    // ============ CRUD ============

    @Transactional
    public User adminCreate(String email, String fullName, String phone, Role role, boolean enabled) {
        if (email == null || email.isBlank())
            throw new IllegalArgumentException("Email обязателен");
        if (userRepository.existsByEmail(email.toLowerCase()))
            throw new IllegalArgumentException("Email уже занят");

        User u = new User();
        u.setEmail(email.toLowerCase().trim());
        u.setFullName(fullName == null || fullName.isBlank() ? email : fullName.trim());
        u.setPhone(phone == null ? null : phone.trim());
        u.setRole(role == null ? Role.ROLE_USER : role);
        u.setEnabled(enabled);
        return userRepository.save(u);
    }

    @Transactional
    public User adminUpdate(Long id, String email, String fullName, String phone,
                            Role role, boolean enabled) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(u.getEmail())) {
            if (userRepository.existsByEmail(email.toLowerCase()))
                throw new IllegalArgumentException("Email уже занят");
            u.setEmail(email.toLowerCase().trim());
        }
        if (fullName != null && !fullName.isBlank()) u.setFullName(fullName.trim());
        u.setPhone(phone == null ? null : phone.trim());
        if (role != null) u.setRole(role);
        u.setEnabled(enabled);
        return userRepository.save(u);
    }

    /**
     * Удаление пользователя. currentAdminEmail — email текущего админа,
     * чтобы запретить удаление самого себя.
     */
    @Transactional
    public void adminDelete(Long id, String currentAdminEmail) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        if (currentAdminEmail != null && u.getEmail().equalsIgnoreCase(currentAdminEmail)) {
            throw new IllegalArgumentException("Нельзя удалить собственную учётную запись");
        }
        userRepository.delete(u);
    }

    @Transactional
    public void adminToggle(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        // Админов не блокируем — у них вход по сертификату, вне сессии их «заблокировать» нельзя
        if (u.getRole() == Role.ROLE_ADMIN) {
            throw new IllegalArgumentException("Нельзя заблокировать администратора");
        }
        u.setEnabled(!u.isEnabled());
        userRepository.save(u);
    }
}
