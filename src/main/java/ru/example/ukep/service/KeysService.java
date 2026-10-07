package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Информация о ключах шифрования (file.key, pii.key).
 * Только для чтения — никаких изменений отсюда.
 */
@Service
public class KeysService {

    private static final Logger log = LoggerFactory.getLogger(KeysService.class);

    private final Path fileKeyPath;
    private final Path piiKeyPath;

    public KeysService(
            @Value("${app.file-encryption-key-path:/opt/afina/secrets/file.key}")
            String fileKey,
            @Value("${app.pii-key-path:/opt/afina/secrets/pii.key}")
            String piiKey) {
        this.fileKeyPath = Path.of(fileKey);
        this.piiKeyPath = Path.of(piiKey);
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

    public List<KeyInfo> listAll() {
        List<KeyInfo> result = new ArrayList<>();
        result.add(inspect("file.key", "Шифрование файлов документов (AES-256-GCM)", fileKeyPath));
        result.add(inspect("pii.key", "Шифрование персональных данных (AES-256-GCM)", piiKeyPath));
        return result;
    }

    private KeyInfo inspect(String name, String description, Path path) {
        if (!Files.exists(path)) {
            return new KeyInfo(name, description, path.toString(),
                    false, 0, "—", "—", "—",
                    "Файл отсутствует");
        }
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            String modifiedAt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault()).format(modified);

            String perms = "—";
            String owner = "—";
            try {
                PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class);
                perms = String.format("%04o", 0); // placeholder
                // Posix perms: грубо через Files.getPosixFilePermissions
                var p = Files.getPosixFilePermissions(path);
                StringBuilder sb = new StringBuilder();
                for (var perm : p) sb.append(perm.name().charAt(0));
                perms = sb.toString();
                owner = attrs.owner().getName() + ":" + attrs.group().getName();
            } catch (UnsupportedOperationException ignored) {
                // Windows
            }

            String problem = null;
            if (size != 45) {
                problem = "Размер не 45 байт (ожидается base64 от 32 байт)";
            }
            if (!perms.startsWith("r--------") && !perms.equals("rw-------")) {
                problem = "Права отличаются от 600 (текущие: " + perms + ")";
            }

            return new KeyInfo(name, description, path.toString(),
                    true, size, modifiedAt, perms, owner, problem);
        } catch (IOException e) {
            return new KeyInfo(name, description, path.toString(),
                    false, 0, "—", "—", "—",
                    "Ошибка чтения: " + e.getMessage());
        }
    }

    public Path getFileKeyPath() { return fileKeyPath; }
    public Path getPiiKeyPath() { return piiKeyPath; }
}
