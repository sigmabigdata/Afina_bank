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

    @Value("${app.db-superuser:postgres}")
    private String superUser;

    @Value("${app.db-superuser-password:}")
    private String superPassword;

    public record BackupFile(String name, long size, Instant createdAt, String sizePretty) {}

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

    public String create() throws IOException, InterruptedException {
        Files.createDirectories(Paths.get(backupsPath));
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        String fileName = "afina_" + stamp + ".sql.gz";
        Path out = Paths.get(backupsPath).resolve(fileName);

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
        Process proc = pb.start();

        final StringBuilder stderrBuf = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    stderrBuf.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        }, "pg_dump-stderr");
        errThread.setDaemon(true);
        errThread.start();

        try (InputStream dumpOut = proc.getInputStream();
             GZIPOutputStream gz = new GZIPOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(out)))) {
            dumpOut.transferTo(gz);
        }

        int rc = proc.waitFor();
        errThread.join(2000);
        if (rc != 0) {
            Files.deleteIfExists(out);
            String err = stderrBuf.toString().trim();
            log.error("pg_dump stderr:\n{}", err);
            throw new IOException("pg_dump exit " + rc +
                    (err.isEmpty() ? "" : ": " + err));
        }

        log.info("Backup created: {} ({} bytes)", fileName, Files.size(out));
        return fileName;
    }

    public void delete(String name) throws IOException {
        Path file = safePath(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл не найден: " + name);
        }
        Files.delete(file);
        log.info("Backup deleted: {}", name);
    }

    public byte[] read(String name) throws IOException {
        Path file = safePath(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл не найден: " + name);
        }
        return Files.readAllBytes(file);
    }

    public String restore(String name) throws IOException, InterruptedException {
        Path file = safePath(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Файл не найден: " + name);
        }

        log.warn("Restoring database from {}...", name);

        String safety = create();
        log.info("Safety backup before restore: {}", safety);

        terminateConnections();
        Thread.sleep(1000);

        runPsqlCommandAsSuperuser(
            "DROP SCHEMA public CASCADE; " +
            "CREATE SCHEMA public; " +
            "ALTER SCHEMA public OWNER TO afina_migrator; " +
            "GRANT USAGE ON SCHEMA public TO afina_app, afina_auditor;"
        );

        runPsqlFromFileAsSuperuser(file, name.endsWith(".gz"));

        runPsqlCommandAsSuperuser(
            "DO $$ DECLARE r RECORD; BEGIN " +
            "FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP " +
            "EXECUTE 'ALTER TABLE public.' || quote_ident(r.tablename) || ' OWNER TO afina_migrator'; END LOOP; " +
            "FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname='public' LOOP " +
            "EXECUTE 'ALTER SEQUENCE public.' || quote_ident(r.sequencename) || ' OWNER TO afina_migrator'; END LOOP; " +
            "END $$; " +
            "GRANT ALL ON SCHEMA public TO afina_migrator; " +
            "GRANT USAGE ON SCHEMA public TO afina_app, afina_auditor; " +
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO afina_app; " +
            "GRANT SELECT ON ALL TABLES IN SCHEMA public TO afina_auditor; " +
            "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO afina_app; " +
            "GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO afina_auditor; " +
            "DO $$ BEGIN " +
            "  IF EXISTS (SELECT 1 FROM information_schema.tables " +
            "             WHERE table_schema='public' AND table_name='flyway_schema_history') THEN " +
            "    EXECUTE 'GRANT ALL ON flyway_schema_history TO afina_migrator'; " +
            "    EXECUTE 'GRANT SELECT ON flyway_schema_history TO afina_app, afina_auditor'; " +
            "  END IF; " +
            "END $$;"
        );

        log.info("Database restored from {}. Safety-backup: {}", name, safety);
        return safety;
    }

    private void runPsqlCommand(String sql) throws IOException, InterruptedException {
        DbParams db = parseDbParams();
        ProcessBuilder pb = new ProcessBuilder(
                "psql", "-h", db.host, "-p", String.valueOf(db.port),
                "-U", dbUser, "-d", db.name,
                "-v", "ON_ERROR_STOP=1", "-c", sql
        );
        pb.environment().put("PGPASSWORD", dbPassword);
        runPsqlProcess(pb, "psql -c");
    }

    private void runPsqlFromFile(Path file, boolean gz) throws IOException, InterruptedException {
        DbParams db = parseDbParams();
        ProcessBuilder pb = new ProcessBuilder(
                "psql", "-h", db.host, "-p", String.valueOf(db.port),
                "-U", dbUser, "-d", db.name,
                "-v", "ON_ERROR_STOP=1", "--single-transaction"
        );
        pb.environment().put("PGPASSWORD", dbPassword);
        Process proc = pb.start();

        final StringBuilder stderrBuf = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) stderrBuf.append(line).append("\n");
            } catch (IOException ignored) {}
        }, "psql-stderr");
        errThread.setDaemon(true);
        errThread.start();

        try (OutputStream stdin = proc.getOutputStream();
             InputStream fileIn = Files.newInputStream(file);
             InputStream in = gz ? new GZIPInputStream(fileIn) : fileIn) {
            in.transferTo(stdin);
            stdin.flush();
        }

        int rc = proc.waitFor();
        errThread.join(2000);
        if (rc != 0) {
            String err = stderrBuf.toString().trim();
            log.error("psql restore stderr:\n{}", err);
            throw new IOException("psql exit " + rc +
                    (err.isEmpty() ? "" : ":\n" + err));
        }
        log.info("psql restore completed successfully");
    }

    private void runPsqlProcess(ProcessBuilder pb, String tag)
            throws IOException, InterruptedException {
        Process proc = pb.start();

        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();

        Thread outThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) outBuf.append(line).append("\n");
            } catch (IOException ignored) {}
        }, tag + "-out");

        Thread errThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) errBuf.append(line).append("\n");
            } catch (IOException ignored) {}
        }, tag + "-err");

        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        int rc = proc.waitFor();
        outThread.join(2000);
        errThread.join(2000);

        String out = outBuf.toString().trim();
        String err = errBuf.toString().trim();
        if (!out.isEmpty()) log.debug("{} stdout:\n{}", tag, out);

        if (rc != 0) {
            log.error("{} exit {} stderr:\n{}", tag, rc, err);
            throw new IOException(tag + " exit " + rc +
                    (err.isEmpty() ? "" : ":\n" + err));
        }
    }

    private void terminateConnections() {
        try {
            DbParams db = parseDbParams();
            ProcessBuilder pb = new ProcessBuilder(
                    "psql", "-h", db.host, "-p", String.valueOf(db.port),
                    "-U", dbUser, "-d", db.name, "-c",
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                    "WHERE datname = current_database() AND pid <> pg_backend_pid()"
            );
            pb.environment().put("PGPASSWORD", dbPassword);
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

    // ===================== SUPERUSER-операции (только для restore) =====================

    private void runPsqlCommandAsSuperuser(String sql) throws IOException, InterruptedException {
        DbParams db = parseDbParams();
        ProcessBuilder pb = new ProcessBuilder(
                "psql", "-h", db.host, "-p", String.valueOf(db.port),
                "-U", superUser, "-d", db.name,
                "-v", "ON_ERROR_STOP=1", "-c", sql
        );
        pb.environment().put("PGPASSWORD", superPassword);
        runPsqlProcess(pb, "psql -c (super)");
    }

    private void runPsqlFromFileAsSuperuser(Path file, boolean gz) throws IOException, InterruptedException {
        DbParams db = parseDbParams();
        ProcessBuilder pb = new ProcessBuilder(
                "psql", "-h", db.host, "-p", String.valueOf(db.port),
                "-U", superUser, "-d", db.name,
                "-v", "ON_ERROR_STOP=1"
        );
        pb.environment().put("PGPASSWORD", superPassword);
        Process proc = pb.start();

        final StringBuilder stderrBuf = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) stderrBuf.append(line).append("\n");
            } catch (IOException ignored) {}
        }, "psql-super-stderr");
        errThread.setDaemon(true);
        errThread.start();

        try (OutputStream stdin = proc.getOutputStream();
             InputStream fileIn = Files.newInputStream(file);
             InputStream in = gz ? new GZIPInputStream(fileIn) : fileIn) {
            in.transferTo(stdin);
            stdin.flush();
        }

        int rc = proc.waitFor();
        errThread.join(2000);
        if (rc != 0) {
            String err = stderrBuf.toString().trim();
            log.error("psql (super) restore stderr:\n{}", err);
            throw new IOException("psql (super) exit " + rc +
                    (err.isEmpty() ? "" : ":\n" + err));
        }
        log.info("psql restore (superuser) completed successfully");
    }
}
