package ru.example.ukep.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.MonitorEvent;
import ru.example.ukep.repository.MonitorEventRepository;
import ru.example.ukep.service.MonitoringService;
import ru.example.ukep.service.SettingsService;

import java.util.List;

@Controller
@RequestMapping("/admin/monitor")
public class MonitorController {

    private final MonitorEventRepository events;
    private final SettingsService settings;
    private final MonitoringService monitoring;

    public MonitorController(MonitorEventRepository events,
                             SettingsService settings,
                             MonitoringService monitoring) {
        this.events = events;
        this.settings = settings;
        this.monitoring = monitoring;
    }

    @GetMapping
    public String page(Model model) {
        List<MonitorEvent> recent = events.findAllByOrderByCheckedAtDesc(PageRequest.of(0, 100));
        model.addAttribute("events", recent);
        model.addAttribute("fails", settings.getIntOrDefault("monitor.consecutive_fails", 0));
        model.addAttribute("lastAlert", settings.getOrDefault("monitor.last_alert_at", "—"));
        model.addAttribute("mailTo", settings.getOrDefault("monitor.mail_to", ""));
        model.addAttribute("enabled", settings.getBoolOrDefault("monitor.enabled", true));
        model.addAttribute("totalFail", events.countByStatus("FAIL"));
        model.addAttribute("totalOk", events.countByStatus("OK"));
        return "admin-monitor";
    }

    @PostMapping("/check-now")
    public String checkNow(RedirectAttributes ra) {
        try {
            monitoring.checkHealth();
            ra.addFlashAttribute("ok", "Проверка выполнена, результат в истории");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка: " + e.getMessage());
        }
        return "redirect:/admin/monitor";
    }

    @PostMapping("/reset-counter")
    public String resetCounter(RedirectAttributes ra) {
        settings.set("monitor.consecutive_fails", "0", "admin");
        ra.addFlashAttribute("ok", "Счётчик сбоев сброшен");
        return "redirect:/admin/monitor";
    }
}
