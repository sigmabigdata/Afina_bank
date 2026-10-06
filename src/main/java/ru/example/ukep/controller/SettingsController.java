package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.EmailService;
import ru.example.ukep.service.SettingsService;

@Controller
@RequestMapping("/admin/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsService settings;
    private final AuditService audit;
    private final EmailService emailService;
    private final PiiEncryptor pii;

    public SettingsController(SettingsService settings,
                              AuditService audit,
                              EmailService emailService,
                              PiiEncryptor pii) {
        this.settings = settings;
        this.audit = audit;
        this.emailService = emailService;
        this.pii = pii;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("settings", settings.getAll());
        // SMTP-значения для формы (пароль маскируем)
        model.addAttribute("smtpHost", settings.getOrDefault("smtp.host", ""));
        model.addAttribute("smtpPort", settings.getOrDefault("smtp.port", "465"));
        model.addAttribute("smtpUsername", settings.getOrDefault("smtp.username", ""));
        model.addAttribute("smtpFrom", settings.getOrDefault("smtp.from", ""));
        model.addAttribute("smtpSsl", settings.getBoolOrDefault("smtp.ssl", true));
        boolean hasPassword = !settings.getOrDefault("smtp.password", "").isBlank();
        model.addAttribute("smtpHasPassword", hasPassword);
        return "admin-settings";
    }

    @PostMapping
    public String save(@RequestParam(required = false) String monitorMailTo,
                       @RequestParam(required = false) String monitorEnabled,
                       @RequestParam(required = false) String revocationMode,
                       @RequestParam(required = false) String rateLimitLogin,
                       @RequestParam(required = false) String rateLimitWindow,
                       @RequestParam(required = false) String requireCrl,
                       @RequestParam(required = false) String smtpHost,
                       @RequestParam(required = false) String smtpPort,
                       @RequestParam(required = false) String smtpUsername,
                       @RequestParam(required = false) String smtpPassword,
                       @RequestParam(required = false) String smtpFrom,
                       @RequestParam(required = false) String smtpSsl,
                       Authentication auth,
                       RedirectAttributes ra) {
        String who = auth != null ? auth.getName() : "unknown";
        try {
            // Основные
            settings.set("monitor.mail_to", monitorMailTo == null ? "" : monitorMailTo.trim(), who);
            settings.set("monitor.enabled", monitorEnabled != null ? "true" : "false", who);
            settings.set("revocation.mode", revocationMode == null ? "soft" : revocationMode, who);
            settings.set("auth.rate_limit.login", rateLimitLogin == null ? "5" : rateLimitLogin, who);
            settings.set("auth.rate_limit.window_min",
                    rateLimitWindow == null ? "15" : rateLimitWindow, who);
            settings.set("signature.require_crl", requireCrl != null ? "true" : "false", who);

            // SMTP
            settings.set("smtp.host", smtpHost == null ? "" : smtpHost.trim(), who);
            settings.set("smtp.port", smtpPort == null ? "465" : smtpPort.trim(), who);
            settings.set("smtp.username", smtpUsername == null ? "" : smtpUsername.trim(), who);
            settings.set("smtp.from", smtpFrom == null ? "" : smtpFrom.trim(), who);
            settings.set("smtp.ssl", smtpSsl != null ? "true" : "false", who);

            // Пароль — только если введён новый (не пустая строка)
            if (smtpPassword != null && !smtpPassword.isBlank()) {
                settings.setSmtpPasswordEncrypted(smtpPassword, pii, who);
            }

            audit.settingsUpdate(who, "monitor/revocation/rate-limit/smtp");
            ra.addFlashAttribute("ok", "Настройки сохранены");
            log.info("Settings saved by {}", who);
        } catch (Exception e) {
            log.error("Ошибка сохранения настроек", e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/test-email")
    public String testEmail(@RequestParam String to,
                            Authentication auth,
                            RedirectAttributes ra) {
        String who = auth != null ? auth.getName() : "unknown";
        if (to == null || to.isBlank() || !to.contains("@")) {
            ra.addFlashAttribute("err", "Укажите корректный email");
            return "redirect:/admin/settings";
        }
        try {
            emailService.sendTest(to.trim());
            ra.addFlashAttribute("ok", "Тестовое письмо отправлено на " + to.trim());
            log.info("Test email sent to {} by {}", to, who);
        } catch (Exception e) {
            log.error("Test email failed", e);
            ra.addFlashAttribute("err", "Ошибка отправки: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }
}
