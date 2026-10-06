package ru.example.ukep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Фильтр rate-limit для критичных endpoints.
 * Проверяет URL и применяет лимит по IP (и по email для /login).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitService rateLimit;

    public RateLimitFilter(RateLimitService rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        String method = req.getMethod();
        String ip = clientIp(req);

        // /login (POST) — 5 попыток / 15 мин на IP+email
        if ("/login".equals(path) && "POST".equalsIgnoreCase(method)) {
            String email = req.getParameter("email");
            String emailKey = (email == null || email.isBlank())
                    ? "anon"
                    : email.toLowerCase().trim();
            if (!check(req, resp, "login:" + ip + ":" + emailKey, 5, Duration.ofMinutes(15))) {
                return;
            }
        }

        // /admin/challenge — 10 / 15 мин на IP
        else if ("/admin/challenge".equals(path)) {
            if (!check(req, resp, "challenge:" + ip, 10, Duration.ofMinutes(15))) {
                return;
            }
        }

        // /admin/cert-login — 5 / 15 мин на IP
        else if ("/admin/cert-login".equals(path) && "POST".equalsIgnoreCase(method)) {
            if (!check(req, resp, "certlogin:" + ip, 5, Duration.ofMinutes(15))) {
                return;
            }
        }

        // /login/confirm — 20 / 15 мин на IP (защита от перебора токена)
        else if ("/login/confirm".equals(path)) {
            if (!check(req, resp, "confirm:" + ip, 20, Duration.ofMinutes(15))) {
                return;
            }
        }

        chain.doFilter(req, resp);
    }

    private boolean check(HttpServletRequest req, HttpServletResponse resp,
                          String key, int limit, Duration window) throws IOException {
        if (rateLimit.tryConsume(key, limit, window)) {
            return true;
        }
        long retryAfter = rateLimit.retryAfterSeconds(key);
        log.warn("429 {} from {} (retry-after {}s)", req.getRequestURI(),
                clientIp(req), retryAfter);
        resp.setStatus(429);
        resp.setHeader("Retry-After", String.valueOf(retryAfter));
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"Слишком много попыток. Попробуйте позже.\"}");
        return false;
    }

    /** Реальный IP с учётом X-Forwarded-For (Caddy). */
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Берём первый IP в цепочке (реальный клиент)
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return req.getRemoteAddr();
    }
}
