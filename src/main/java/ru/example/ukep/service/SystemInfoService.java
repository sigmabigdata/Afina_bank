package ru.example.ukep.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Собирает метрики сервера и БД для админ-панели.
 * Читает /proc (Linux) и данные через JdbcTemplate.
 */
@Service
public class SystemInfoService {

    private final JdbcTemplate jdbc;

    public SystemInfoService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Uptime приложения. */
    public Duration getJvmUptime() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        return Duration.ofMillis(rt.getUptime());
    }

    /** Средняя нагрузка (1/5/15 мин). */
    public double[] getLoadAverage() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    ManagementFactory.getOperatingSystemMXBean();
            double load = os.getSystemLoadAverage();
            return new double[]{load, load, load};
        } catch (Exception e) {
            return new double[]{0, 0, 0};
        }
    }

    /** RAM: всего/использовано/свободно (МБ). */
    public Map<String, Long> getMemory() {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            // Читаем /proc/meminfo (Linux)
            List<String> lines = Files.readAllLines(Path.of("/proc/meminfo"));
            long total = 0, available = 0, free = 0;
            for (String line : lines) {
                if (line.startsWith("MemTotal:"))        total     = parseKb(line);
                else if (line.startsWith("MemAvailable:")) available = parseKb(line);
                else if (line.startsWith("MemFree:"))      free      = parseKb(line);
            }
            long used = total - available;
            result.put("total",     total / 1024);      // MB
            result.put("used",      used  / 1024);
            result.put("available", available / 1024);
            result.put("free",      free / 1024);
            result.put("percent",   total > 0 ? (used * 100 / total) : 0);
        } catch (Exception e) {
            // fallback — данные JVM
            Runtime rt = Runtime.getRuntime();
            long total = rt.totalMemory();
            long free  = rt.freeMemory();
            result.put("total",     total / 1024 / 1024);
            result.put("used",      (total - free) / 1024 / 1024);
            result.put("available", free / 1024 / 1024);
            result.put("free",      free / 1024 / 1024);
            result.put("percent",   total > 0 ? ((total - free) * 100 / total) : 0);
        }
        return result;
    }

    /** CPU: количество ядер + примерная загрузка (%). */
    public Map<String, Object> getCpu() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cores", Runtime.getRuntime().availableProcessors());
        result.put("loadAvg", getLoadAverage());
        result.put("percent", cpuPercent());
        return result;
    }

    private long parseKb(String line) {
        try {
            String v = line.replaceAll("[^0-9]", " ").trim().split("\\s+")[0];
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Примерная загрузка CPU через /proc/stat. */
    private long cpuPercent() {
        try {
            long[] s1 = readCpuStat();
            Thread.sleep(200);
            long[] s2 = readCpuStat();
            long idle  = s2[3] - s1[3];
            long total = 0;
            for (int i = 0; i < s2.length; i++) total += s2[i] - s1[i];
            if (total <= 0) return 0;
            return (total - idle) * 100 / total;
        } catch (Exception e) {
            return 0;
        }
    }

    private long[] readCpuStat() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("/proc/stat"));
        for (String line : lines) {
            if (line.startsWith("cpu ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                long[] arr = new long[parts.length];
                for (int i = 0; i < parts.length; i++) arr[i] = Long.parseLong(parts[i]);
                return arr;
            }
        }
        return new long[10];
    }

    /** Диск: общий/использованный/свободный (ГБ). */
    public Map<String, Long> getDisk() {
        Map<String, Long> result = new LinkedHashMap<>();
        File root = new File("/");
        long total = root.getTotalSpace();
        long free  = root.getFreeSpace();
        long used  = total - free;
        result.put("total",   total / 1024 / 1024 / 1024);
        result.put("used",    used  / 1024 / 1024 / 1024);
        result.put("free",    free  / 1024 / 1024 / 1024);
        result.put("percent", total > 0 ? (used * 100 / total) : 0);
        return result;
    }

    /** БД: размер, таблицы, миграции, соединения. */
    public Map<String, Object> getDatabaseStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Long size = jdbc.queryForObject(
                "SELECT pg_database_size(current_database())", Long.class);
            result.put("sizeBytes", size == null ? 0 : size);
            result.put("sizePretty", size == null ? "0 B" : prettySize(size));
        } catch (Exception e) {
            result.put("sizePretty", "n/a");
        }

        try {
            List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT relname AS name, " +
                "       n_live_tup AS rows, " +
                "       pg_total_relation_size(relid) AS size " +
                "FROM pg_stat_user_tables " +
                "ORDER BY pg_total_relation_size(relid) DESC");
            List<Map<String, Object>> pretty = new ArrayList<>();
            for (Map<String, Object> t : tables) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.get("name"));
                m.put("rows", t.get("rows"));
                Long sz = ((Number) t.get("size")).longValue();
                m.put("size", prettySize(sz));
                pretty.add(m);
            }
            result.put("tables", pretty);
        } catch (Exception e) {
            result.put("tables", List.of());
        }

        try {
            List<Map<String, Object>> migr = jdbc.queryForList(
                "SELECT version, description, success, installed_on " +
                "FROM flyway_schema_history ORDER BY installed_rank");
            result.put("migrations", migr);
        } catch (Exception e) {
            result.put("migrations", List.of());
        }

        try {
            Long conns = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()",
                Long.class);
            result.put("connections", conns);
        } catch (Exception e) {
            result.put("connections", 0);
        }

        try {
            Long users = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
            Long docs  = jdbc.queryForObject("SELECT count(*) FROM documents", Long.class);
            Long sigs  = jdbc.queryForObject("SELECT count(*) FROM document_signatures", Long.class);
            result.put("usersCount", users);
            result.put("docsCount", docs);
            result.put("sigsCount", sigs);
        } catch (Exception ignored) {}

        return result;
    }

    /** Информация о JVM / OS / сборке. */
    public Map<String, String> getRuntimeInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("osName", System.getProperty("os.name"));
        info.put("osArch", System.getProperty("os.arch"));
        info.put("osVersion", System.getProperty("os.version"));
        info.put("userDir", System.getProperty("user.dir"));
        info.put("timezone", System.getProperty("user.timezone"));
        try {
            File f = new File(System.getProperty("user.dir"));
            info.put("workingDir", f.getAbsolutePath());
        } catch (Exception ignored) {}
        return info;
    }

    public String prettySize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024) + " MB";
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
