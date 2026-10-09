package ru.example.ukep.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class LogService {

    @Value("${app.logs-path:/app/logs}")
    private String logsPath;

    public Path getLogFile() {
        return Paths.get(logsPath).resolve("app.log");
    }

    /** Последние N строк лога (tail). */
    public List<String> tail(int lines) {
        Path log = getLogFile();
        if (!Files.isRegularFile(log)) {
            return List.of("(лог не найден: " + log + ")");
        }
        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - lines * 200L);
            raf.seek(start);
            List<String> all = new ArrayList<>();
            String line;
            while ((line = raf.readLine()) != null) {
                all.add(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
            int from = Math.max(0, all.size() - lines);
            return all.subList(from, all.size());
        } catch (IOException e) {
            return List.of("(ошибка чтения: " + e.getMessage() + ")");
        }
    }
}
