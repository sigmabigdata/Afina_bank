package ru.example.ukep.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.example.ukep.entity.AuditEvent;
import ru.example.ukep.repository.AuditEventRepository;
import ru.example.ukep.service.AuditCleanupService;
import ru.example.ukep.service.SettingsService;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/audit")
public class AuditController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditController.class);

    private static final DateTimeFormatter CSV_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Максимум загружаем в память для фильтрации
    private static final int MAX_LOAD = 10_000;

    private final AuditEventRepository repo;
    private final AuditCleanupService cleanupService;
    private final SettingsService settings;

    public AuditController(AuditEventRepository repo,
                           AuditCleanupService cleanupService,
                           SettingsService settings) {
        this.repo = repo;
        this.cleanupService = cleanupService;
        this.settings = settings;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String type,
                       @RequestParam(required = false) String actor,
                       @RequestParam(required = false) String result,
                       @RequestParam(name = "from", required = false) String fromStr,
                       @RequestParam(name = "to", required = false) String toStr,
                       @RequestParam(defaultValue = "200") int limit,
                       Model model) {

        int safeLimit = Math.min(Math.max(limit, 10), 2000);

        LocalDate from = parseDate(fromStr);
        LocalDate to = parseDate(toStr);

        log.info("audit filter: type='{}' actor='{}' result='{}' from='{}'->{} to='{}'->{}",
                type, actor, result, fromStr, from, toStr, to);
        List<AuditEvent> filtered = filterInMemory(type, actor, result, from, to);
        List<AuditEvent> page = filtered.stream().limit(safeLimit).toList();

        model.addAttribute("events", page);
        model.addAttribute("eventTypes", repo.findDistinctEventTypes());
        model.addAttribute("type", type == null ? "" : type);
        model.addAttribute("actor", actor == null ? "" : actor);
        model.addAttribute("result", result == null ? "" : result);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("limit", safeLimit);
        model.addAttribute("totalFound", filtered.size());
        model.addAttribute("retentionDays", settings.getIntOrDefault("audit.retention_days", 365));
        return "admin-audit";
    }

    @GetMapping("/export.csv")
    public void exportCsv(@RequestParam(required = false) String type,
                          @RequestParam(required = false) String actor,
                          @RequestParam(required = false) String result,
                          @RequestParam(name = "from", required = false) String fromStr,
                          @RequestParam(name = "to", required = false) String toStr,
                          HttpServletResponse resp) throws Exception {

        LocalDate from = parseDate(fromStr);
        LocalDate to = parseDate(toStr);

        List<AuditEvent> events = filterInMemory(type, actor, result, from, to);

        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition",
                "attachment; filename=\"audit-" + LocalDate.now() + ".csv\"");

        PrintWriter w = resp.getWriter();
        w.write('\ufeff');
        w.println("event_time,event_type,result,actor_email,actor_role,actor_ip," +
                  "target_type,target_id,target_info,details");

        for (AuditEvent e : events) {
            w.println(String.join(",",
                    csv(CSV_DATE.format(e.getEventTime())),
                    csv(e.getEventType()),
                    csv(e.getResult()),
                    csv(e.getActorEmail()),
                    csv(e.getActorRole()),
                    csv(e.getActorIp()),
                    csv(e.getTargetType()),
                    csv(e.getTargetId()),
                    csv(e.getTargetInfo()),
                    csv(e.getDetails())));
        }
        w.flush();
    }

    /**
     * Фильтрация в памяти.
     * Hibernate 6 + PostgreSQL не могут типизировать null-параметры
     * (Instant / String) в JPQL, поэтому фильтруем после загрузки.
     */
    private List<AuditEvent> filterInMemory(String type, String actor, String result,
                                             LocalDate from, LocalDate to) {
        Instant fromI = from == null ? null
                : from.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant toI = to == null ? null
                : to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        String typeF = (type == null || type.isBlank()) ? null : type;
        String actorF = (actor == null || actor.isBlank()) ? null : actor.toLowerCase().trim();
        String resultF = (result == null || result.isBlank()) ? null : result;

        return repo.findAllByOrderByEventTimeDesc(PageRequest.of(0, MAX_LOAD))
                .stream()
                .filter(e -> typeF == null || typeF.equals(e.getEventType()))
                .filter(e -> resultF == null || resultF.equals(e.getResult()))
                .filter(e -> actorF == null
                        || (e.getActorEmail() != null
                            && e.getActorEmail().toLowerCase().contains(actorF)))
                .filter(e -> fromI == null || !e.getEventTime().isBefore(fromI))
                .filter(e -> toI == null || e.getEventTime().isBefore(toI))
                .collect(Collectors.toList());
    }

    @org.springframework.web.bind.annotation.PostMapping("/cleanup")
    public String cleanupNow(org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        int days = settings.getIntOrDefault("audit.retention_days", 365);
        if (days <= 0) {
            ra.addFlashAttribute("err", "Retention = 0, очистка отключена");
            return "redirect:/admin/audit";
        }
        try {
            long deleted = cleanupService.cleanup(days);
            ra.addFlashAttribute("ok", "Удалено записей: " + deleted + " (старше " + days + " дней)");
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка очистки: " + e.getMessage());
        }
        return "redirect:/admin/audit";
    }

    @org.springframework.web.bind.annotation.PostMapping("/retention")
    public String setRetention(@RequestParam int days,
                               java.security.Principal auth,
                               org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        if (days < 0 || days > 3650) {
            ra.addFlashAttribute("err", "Допустимо 0..3650 дней");
            return "redirect:/admin/audit";
        }
        settings.set("audit.retention_days", String.valueOf(days),
                auth != null ? auth.getName() : "admin");
        ra.addFlashAttribute("ok", "Retention: " + days + " дней");
        return "redirect:/admin/audit";
    }

    /** Парсит дату из query param (ISO format: yyyy-MM-dd). */
    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            log.warn("Не удалось распарсить дату '{}': {}", s, e.getMessage());
            return null;
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
