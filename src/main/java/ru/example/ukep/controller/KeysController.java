package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.KeysService;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/admin/keys")
public class KeysController {

    private static final Logger log = LoggerFactory.getLogger(KeysController.class);

    private final KeysService keys;
    private final AuditService audit;

    public KeysController(KeysService keys, AuditService audit) {
        this.keys = keys;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("keys", keys.listAll());
        model.addAttribute("lastBackupAt", keys.getLastBackupAt());
        model.addAttribute("downloadCount", keys.getDownloadCount());
        model.addAttribute("daysSinceLastBackup", keys.daysSinceLastBackup());
        return "admin-keys";
    }

    /**
     * Скачать ZIP с ключами. Админ должен будет сохранить его
     * в защищённое место (1Password, сейф).
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(java.security.Principal auth) throws Exception {
        Path fileKey = keys.getFileKeyPath();
        Path piiKey = keys.getPiiKeyPath();

        if (!Files.isReadable(fileKey) || !Files.isReadable(piiKey)) {
            return ResponseEntity.status(500).build();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            addEntry(zip, "file.key", Files.readAllBytes(fileKey));
            addEntry(zip, "pii.key", Files.readAllBytes(piiKey));
            addEntry(zip, "README.txt", """
                    Афина · Бэкап ключей шифрования
                    Скачано: %s

                    В архиве:
                    - file.key — AES-256-GCM для файлов документов
                    - pii.key  — AES-256-GCM для персональных данных (email, phone, ФИО в БД)

                    ⚠️  Без этих ключей восстановить данные невозможно.
                    ⚠️  Храните архив в защищённом месте (1Password, сейф).
                    ⚠️  Никогда не пересылайте по открытым каналам.
                    """.formatted(LocalDate.now()).getBytes());
        }

        String zipName = "afina-keys-" + LocalDate.now() + ".zip";
        String who = auth != null ? auth.getName() : "unknown";
        log.warn("Key backup downloaded by {} ({} bytes)", who, baos.size());
        keys.recordBackupDownload(who);
        audit.event("KEYS_DOWNLOAD", "WARN", who, "ROLE_ADMIN",
                "KEYS", "file.key+pii.key", zipName,
                "Скачан архив с ключами шифрования");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + zipName + "\"")
                .body(baos.toByteArray());
    }

    private void addEntry(ZipOutputStream zip, String name, byte[] data) throws Exception {
        ZipEntry e = new ZipEntry(name);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
