package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    @Value("${app.backups-path:/app/backups}")
    private String backupsPath;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.flyway.user}")
    private String dbUser;

    @Value("${spring.flyway.password}")
    private String dbPassword;

    @Value("${spring.datasource.hikari.pool-name:AfinaHikariPool}")
    private String poolName;

    public record BackupFile(String name, long size, Instant createdAt, String sizePretty) {}

    // ===================== СПИСОК =====================

    public List<BackupFile> list() {
        Path root = Paths.get(backupsPath);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".sql") || n.endsWith(".sql.gz");
                    })
                    .map(p -> {
                        try {
                            long sz = Files.size(p);
                            Instant t = Files.getLastModifiedTime(p).toInstant();
                            return new BackupFile(p.getFileName().toString(), sz, t, prettySize(sz));
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(BackupFile::createdAt).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    // ===================== СОЗДАТЬ =====================

    /** Создаёт бэкап через pg_dump + gzip. Возвращает имя файла. */
    public String create() throws IOException, InterruptedException {
        Files.createDirectories(Paths.get(backupsPath));
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        String fileName = "afina_" + stamp + ".sql.gz";
        Path out = Paths.get(backupsPath).resolve(fileName);

        // pg_dump с параметрами из spring.datasource.url
        DbParams db = parseDbParams();

        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-h", db.host,
                "-p", String.valueOf(db.port),
                "-U", dbUser,
                "-d", db.name,
                "--clean", "--if-exists",
                "--no-owner", "--no-privileges"
        );
        pb.environment().put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);

        Process proc = pb.start();

        // Параллельно: stdout pg_dump → GZIPOutputStream → файл
        try (InputStream dumpOut = proc.getInputStream();
             GZIPOutputStream gz = new GZIPOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(out)))) {
            dumpOut.transferTo(gz);
        }

        int rc = proc.waitFor();
        if (rc != 0) {
            Files.deleteIfExists(out);
            throw new IOException("pg_dump завершился с кодом " + rc);
        }

        log.info("Backup created: {} ({} bytes)", fileName, Files.size(out));
        return fileName;
    }

    // ===================== УДАЛИТЬ =====================

    public void delete(String name) throws IOException {
        Path file = safePath(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл не найден: " + name);
        }
        Files.delete(file);
        log.info("Backup deleted: {}", name);
    }

    // ===================== СКАЧАТЬ =====================

    public byte[] read(String name) throws IOException {
        Path file = safePath(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл не найден: " + name);
        }
        return Files.readAllBytes(file);
    }

    // ===================== ВОССТАНОВИТЬ =====================

    /**
     * Восстанавливает БД из бэкапа.
     * Перед восстановлением автоматически создаёт бэкап "before_restore_<ts>".
     * После успешного восстановления приложение должно быть перезапущено.
     */
    public String restore(String name) throws IOException, InterruptedException {
        Path file = safePath(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл не найден: " + name);
        }

        log.warn("Restoring database from {}...", name);

        // 1. Сначала делаем safety-бэкап текущего состояния
        String safety = create();
        log.info("Safety backup before restore: {}", safety);

        // 2. Убиваем активные соединения к БД (кроме своего)
        terminateConnections();

        // 3. Подаём дамп в psql
        DbParams db = parseDbParams();
        ProcessBuilder pb = new ProcessBuilder(
                "psql",
                "-h", db.host,
                "-p", String.valueOf(db.port),
                "-U", dbUser,
                "-d", db.name,
                "-q", "--single-transaction",
                "-v", "ON_ERROR_STOP=1"
        );
        pb.environment().put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);

        Process proc = pb.start();

        // На вход psql → распакованный файл
        try (OutputStream stdin = proc.getOutputStream();
             InputStream fileIn = Files.newInputStream(file);
             InputStream in = name.endsWith(".gz")
                     ? new GZIPInputStream(fileIn)
                     : fileIn) {
            in.transferTo(stdin);
            stdin.flush();
        }

        // Логи
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.debug("[psql] {}", line);
            }
        }

        int rc = proc.waitFor();
        if (rc != 0) {
            throw new IOException("psql завершился с кодом " + rc + ". Safety-backup: " + safety);
        }

        log.info("Database restored from {}. Safety-backup: {}", name, safety);
        return safety;
    }

    // ===================== ВСПОМОГАТЕЛЬНОЕ =====================

    private void terminateConnections() {
        try {
            // Используем psql с inline-командой
            DbParams db = parseDbParams();
            ProcessBuilder pb = new ProcessBuilder(
                    "psql",
                    "-h", db.host,
                    "-p", String.valueOf(db.port),
                    "-U", dbUser,
                    "-d", db.name,
                    "-c",
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                    "WHERE datname = current_database() AND pid <> pg_backend_pid()"
            );
            pb.environment().put("PGPASSWORD", dbPassword);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (br.readLine() != null) { /* skip */ }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            log.info("Terminated active connections to {}", db.name);
        } catch (Exception e) {
            log.warn("Не удалось убить коннекты: {}", e.getMessage());
        }
    }

    private Path safePath(String name) {
        Path root = Paths.get(backupsPath).toAbsolutePath().normalize();
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root)) {
            throw new SecurityException("Недопустимый путь: " + name);
        }
        return file;
    }

    private DbParams parseDbParams() {
        // jdbc:postgresql://host:port/db
        String u = dbUrl.replace("jdbc:postgresql://", "");
        int slash = u.indexOf('/');
        String hostPort = u.substring(0, slash);
        String name = u.substring(slash + 1);
        int q = name.indexOf('?');
        if (q > 0) name = name.substring(0, q);
        String host;
        int port = 5432;
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } else {
            host = hostPort;
        }
        return new DbParams(host, port, name);
    }

    private record DbParams(String host, int port, String name) {}

    public String prettySize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
