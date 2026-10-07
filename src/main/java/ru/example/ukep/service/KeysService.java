package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Информация о ключах шифрования (file.key, pii.key).
 * Только для чтения — никаких изменений отсюда.
 */
@Service
public class KeysService {

    private static final Logger log = LoggerFactory.getLogger(KeysService.class);

    private final Path fileKeyPath;
    private final Path piiKeyPath;
    private final SettingsService settings;

    public KeysService(
            @Value("${app.file-encryption-key-path:/opt/afina/secrets/file.key}")
            String fileKey,
            @Value("${app.pii-key-path:/opt/afina/secrets/pii.key}")
            String piiKey,
            SettingsService settings) {
        this.fileKeyPath = Path.of(fileKey);
        this.piiKeyPath = Path.of(piiKey);
        this.settings = settings;
    }

    public record KeyInfo(
            String name,
            String description,
            String path,
            boolean exists,
            long sizeBytes,
            String modifiedAt,
            String permissions,
            String owner,
            String problem
    ) {}

    /** Дата последнего скачивания бэкапа (ISO Instant или null). */
    public Instant getLastBackupAt() {
        String v = settings.getOrDefault("keys.last_backup_at", "");
        if (v.isBlank()) return null;
        try { return Instant.parse(v); } catch (Exception e) { return null; }
    }

    public long getDownloadCount() {
        return Long.parseLong(settings.getOrDefault("keys.download_count", "0"));
    }

    public long daysSinceLastBackup() {
        Instant last = getLastBackupAt();
        if (last == null) return -1;
        return ChronoUnit.DAYS.between(last, Instant.now());
    }

    /** Записать факт скачивания бэкапа. */
    public void recordBackupDownload(String by) {
        settings.set("keys.last_backup_at", Instant.now().toString(), by);
        long count = getDownloadCount() + 1;
        settings.set("keys.download_count", String.valueOf(count), by);
    }

    public List<KeyInfo> listAll() {
        List<KeyInfo> result = new ArrayList<>();
        result.add(inspect("file.key", "Файлы документов (AES-256-GCM)", fileKeyPath));
        result.add(inspect("pii.key", "Персональные данные: email, телефон, ФИО в документах (AES-256-GCM)", piiKeyPath));
        return result;
    }

    private KeyInfo inspect(String name, String description, Path path) {
        if (!Files.exists(path)) {
            return new KeyInfo(name, description, path.toString(),
                    false, 0, "—", "—", "—", "Файл отсутствует");
        }
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            String modifiedAt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault()).format(modified);

            String perms = "—";
            String owner = "—";
            String problem = null;

            try {
                PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class);
                perms = PosixFilePermissions.toString(attrs.permissions());
                owner = attrs.owner().getName() + ":" + attrs.group().getName();

                if (!"rw-------".equals(perms)) {
                    problem = "Права не 600 (текущие: " + perms + ")";
                }
            } catch (UnsupportedOperationException ignored) {
                // Windows/не-POSIX
            }

            if (size != 45) {
                problem = (problem != null ? problem + "; " : "")
                        + "Размер не 45 байт (текущий: " + size + ")";
            }

            return new KeyInfo(name, description, path.toString(),
                    true, size, modifiedAt, perms, owner, problem);
        } catch (IOException e) {
            return new KeyInfo(name, description, path.toString(),
                    false, 0, "—", "—", "—", "Ошибка чтения: " + e.getMessage());
        }
    }

    public Path getFileKeyPath() { return fileKeyPath; }
    public Path getPiiKeyPath() { return piiKeyPath; }
}
