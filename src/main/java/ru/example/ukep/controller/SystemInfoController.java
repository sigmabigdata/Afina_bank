package ru.example.ukep.controller;

import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.service.BackupService;
import ru.example.ukep.service.LogService;
import ru.example.ukep.service.SystemInfoService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/admin/system")
public class SystemInfoController {

    private final SystemInfoService sys;
    private final BackupService backups;
    private final LogService logs;

    public SystemInfoController(SystemInfoService sys,
                                BackupService backups,
                                LogService logs) {
        this.sys = sys;
        this.backups = backups;
        this.logs = logs;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("uptime", formatDuration(sys.getJvmUptime().getSeconds()));
        model.addAttribute("memory", sys.getMemory());
        model.addAttribute("cpu", sys.getCpu());
        model.addAttribute("disk", sys.getDisk());
        model.addAttribute("db", sys.getDatabaseStats());
        model.addAttribute("runtime", sys.getRuntimeInfo());
        model.addAttribute("backups", backups.list());
        return "admin-system";
    }

    @GetMapping("/backups/{name}/download")
    public ResponseEntity<byte[]> downloadBackup(@PathVariable String name) {
        try {
            byte[] data = backups.read(name);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" +
                            URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/logs")
    @ResponseBody
    public String logs(@RequestParam(defaultValue = "200") int lines) {
        int n = Math.min(Math.max(lines, 10), 2000);
        return String.join("\n", logs.tail(n));
    }

    private String formatDuration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (m > 0) sb.append(m).append("м ");
        sb.append(s).append("с");
        return sb.toString();
    }
}
