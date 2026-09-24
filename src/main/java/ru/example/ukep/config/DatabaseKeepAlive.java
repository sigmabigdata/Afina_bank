package ru.example.ukep.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Периодически «пингует» базу данных, чтобы HikariCP не терял соединения
 * после длительного простоя. Особенно актуально для PostgreSQL.
 */
@Component
public class DatabaseKeepAlive {

    private static final Logger log = LoggerFactory.getLogger(DatabaseKeepAlive.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseKeepAlive(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Scheduled(fixedDelay = 300000) // каждые 5 минут
    public void keepAlive() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (result != null && result == 1) {
                log.debug("Database keep-alive: OK");
            }
        } catch (Exception e) {
            log.warn("Database keep-alive failed: {}", e.getMessage());
        }
    }
}
