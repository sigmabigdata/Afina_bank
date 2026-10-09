package ru.example.ukep.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.example.ukep.service.AdminCredentialsFileService;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public ApplicationRunner logAdmins(AdminCredentialsFileService adminFile) {
        return args -> {
            var list = adminFile.readAll();
            log.info("=== Администраторы (admins.env) ===");
            if (list.isEmpty()) {
                log.warn("⚠️  admins.env пуст — вход администратора невозможен!");
                log.warn("    Добавьте строку: CN|SNILS");
            } else {
                for (var r : list) {
                    if (log.isInfoEnabled()) {
                        String snilsTail = r.snils().length() >= 4
                                ? r.snils().substring(r.snils().length() - 4)
                                : "***";
                        log.info("CN='{}', SNILS=***{}", r.cn(), snilsTail);
                    }
                }
            }
        };
    }
}
