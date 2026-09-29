package ru.example.ukep.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class BackupService {

    @Value("${app.backups-path:/app/backups}")
    private String backupsPath;

    public record BackupFile(String name, long size, Instant createdAt, String sizePretty) {}

    public List<BackupFile> list() {
        Path root = Paths.get(backupsPath);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".gz") ||
                                 p.getFileName().toString().endsWith(".sql"))
                    .map(p -> {
                        try {
                            long sz = Files.size(p);
                            Instant t = Files.getLastModifiedTime(p).toInstant();
                            return new BackupFile(
                                    p.getFileName().toString(),
                                    sz,
                                    t,
                                    prettySize(sz));
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(BackupFile::createdAt).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    public byte[] read(String name) throws IOException {
        Path file = Paths.get(backupsPath).resolve(name).normalize();
        Path root = Paths.get(backupsPath).normalize();
        if (!file.startsWith(root)) {
            throw new SecurityException("Недопустимый путь");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл не найден: " + name);
        }
        return Files.readAllBytes(file);
    }

    private String prettySize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
