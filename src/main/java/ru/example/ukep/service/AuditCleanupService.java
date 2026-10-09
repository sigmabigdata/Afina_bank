package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.repository.AuditEventRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Периодическая очистка старых записей аудита.
 *
 * Retention задаётся в app_settings → audit.retention_days (по умолчанию 365).
 * Запускается ежедневно в 04:00.
 */
@Service
public class AuditCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AuditCleanupService.class);

    private final AuditEventRepository repo;
    private final SettingsService settings;

    public AuditCleanupService(AuditEventRepository repo, SettingsService settings) {
        this.repo = repo;
        this.settings = settings;
    }

    /** Ежедневно в 04:00 по TZ контейнера. */
    @Scheduled(cron = "0 0 4 * * *")
    public void dailyCleanup() {
        int days = settings.getIntOrDefault("audit.retention_days", 365);
        if (days <= 0) {
            log.info("audit cleanup: retention={} — отключено", days);
            return;
        }
        try {
            long deleted = doCleanup(days);
            settings.set("audit.last_cleanup_at", Instant.now().toString(), "system");
            settings.set("audit.last_cleanup_deleted", String.valueOf(deleted), "system");
            log.info("audit cleanup: удалено {} записей старше {} дней", deleted, days);
        } catch (Exception e) {
            log.error("audit cleanup failed", e);
        }
    }

    /** Ручной запуск из UI. */
    @Transactional
    public long cleanup(int days) {
        return doCleanup(days);
    }

    /**
     * Тело очистки. Без @Transactional — вызывается из публичных методов
     * (cleanup() и dailyCleanup()), чтобы не было self-invocation
     * (Sonar java:S2229).
     *
     * repo.count() и repo.deleteByEventTimeBefore() сами по себе
     * транзакционные (Spring Data JPA), поэтому корректность сохраняется
     * и без внешней транзакции.
     */
    private long doCleanup(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long before = repo.count();
        long deleted = repo.deleteByEventTimeBefore(cutoff);
        long after = repo.count();
        log.info("audit cleanup: было {}, стало {}, удалено {}", before, after, deleted);
        return deleted;
    }
}
