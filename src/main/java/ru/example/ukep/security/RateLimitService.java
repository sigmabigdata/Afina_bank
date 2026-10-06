package ru.example.ukep.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Простой in-memory rate limiter (fixed window).
 * Подходит для одного инстанса приложения.
 * При горизонтальном масштабировании заменить на Redis-based.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    public record Bucket(AtomicInteger count, Instant resetAt) {
        boolean isExpired() {
            return Instant.now().isAfter(resetAt);
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Проверяет, можно ли выполнить действие. Если да — увеличивает счётчик.
     *
     * @param key    уникальный ключ (например "login:1.2.3.4:user@example.com")
     * @param limit  максимум попыток за окно
     * @param window окно (например Duration.ofMinutes(15))
     * @return true — можно; false — превышен лимит
     */
    public boolean tryConsume(String key, int limit, Duration window) {
        Instant now = Instant.now();
        Bucket bucket = buckets.compute(key, (k, b) -> {
            if (b == null || b.isExpired()) {
                return new Bucket(new AtomicInteger(1), now.plus(window));
            }
            b.count().incrementAndGet();
            return b;
        });
        int used = bucket.count().get();
        if (used > limit) {
            log.warn("Rate limit exceeded: key={}, used={}/{}", key, used, limit);
            return false;
        }
        return true;
    }

    /**
     * Возвращает секунды до сброса окна.
     */
    public long retryAfterSeconds(String key) {
        Bucket b = buckets.get(key);
        if (b == null) return 0;
        long s = Duration.between(Instant.now(), b.resetAt()).getSeconds();
        return Math.max(s, 1);
    }

    /** Периодическая очистка протухших записей (раз в 5 минут). */
    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().isExpired());
        int after = buckets.size();
        if (before != after) {
            log.debug("RateLimit cleanup: {} -> {} buckets", before, after);
        }
    }
}
