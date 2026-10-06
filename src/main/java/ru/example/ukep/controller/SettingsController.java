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
import ru.example.ukep.service.AuditService;
import ru.example.ukep.service.SettingsService;

@Controller
@RequestMapping("/admin/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsService settings;
    private final AuditService audit;

    public SettingsController(SettingsService settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("settings", settings.getAll());
        return "admin-settings";
    }

    @PostMapping
    public String save(@RequestParam(required = false) String monitorMailTo,
                       @RequestParam(required = false) String monitorEnabled,
                       @RequestParam(required = false) String revocationMode,
                       @RequestParam(required = false) String rateLimitLogin,
                       @RequestParam(required = false) String rateLimitWindow,
                       @RequestParam(required = false) String requireCrl,
                       Authentication auth,
                       RedirectAttributes ra) {
        String who = auth != null ? auth.getName() : "unknown";
        try {
            settings.set("monitor.mail_to",
                    monitorMailTo == null ? "" : monitorMailTo.trim(), who);
            settings.set("monitor.enabled",
                    monitorEnabled != null ? "true" : "false", who);
            settings.set("revocation.mode",
                    revocationMode == null ? "soft" : revocationMode, who);
            settings.set("auth.rate_limit.login",
                    rateLimitLogin == null ? "5" : rateLimitLogin, who);
            settings.set("auth.rate_limit.window_min",
                    rateLimitWindow == null ? "15" : rateLimitWindow, who);
            settings.set("signature.require_crl",
                    requireCrl != null ? "true" : "false", who);
            ra.addFlashAttribute("ok", "Настройки сохранены");
            audit.settingsUpdate(who, "monitor/revocation/rate-limit");
            log.info("Settings saved by {}", who);
        } catch (Exception e) {
            log.error("Ошибка сохранения настроек", e);
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }
}
