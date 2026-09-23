package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Хранит список администраторов в файле (по умолчанию ./admins.env).
 * Формат: CN|SNILS — по одной записи на строку.
 * Строки, начинающиеся с # — комментарии.
 */
@Service
public class AdminCredentialsFileService {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialsFileService.class);

    @Value("${app.admins-file:./admins.env}")
    private String adminsFilePath;

    public synchronized List<AdminRecord> readAll() {
        Path path = Path.of(adminsFilePath);
        List<AdminRecord> result = new ArrayList<>();
        if (!Files.exists(path)) return result;

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] parts = s.split("\\|", 2);
                if (parts.length < 2) continue;
                String cn = parts[0].trim();
                String snils = normalizeSnils(parts[1].trim());
                if (!cn.isEmpty() && !snils.isEmpty()) {
                    result.add(new AdminRecord(cn, snils));
                }
            }
        } catch (IOException e) {
            log.error("Не удалось прочитать файл админов: {}", adminsFilePath, e);
        }
        return result;
    }

    /** Ищет администратора по CN и СНИЛС. */
    public Optional<AdminRecord> find(String cn, String snils) {
        if (cn == null || snils == null) return Optional.empty();
        String cnNorm = cn.trim();
        String snilsNorm = normalizeSnils(snils);
        return readAll().stream()
                .filter(r -> r.cn().equalsIgnoreCase(cnNorm) && r.snils().equals(snilsNorm))
                .findFirst();
    }

    public synchronized void writeAll(List<AdminRecord> list) {
        List<String> lines = new ArrayList<>();
        lines.add("# admins.env — автоматически управляется приложением");
        lines.add("# формат: CN|SNILS");
        for (AdminRecord r : list) {
            lines.add(r.cn() + "|" + r.snils());
        }
        try {
            Files.write(Path.of(adminsFilePath), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Не удалось записать файл админов: {}", adminsFilePath, e);
        }
    }

    /** Убирает пробелы/дефисы из СНИЛС: "123-456-789 01" -> "12345678901". */
    private String normalizeSnils(String snils) {
        return snils == null ? "" : snils.replaceAll("[^0-9]", "");
    }

    public record AdminRecord(String cn, String snils) {}
}
