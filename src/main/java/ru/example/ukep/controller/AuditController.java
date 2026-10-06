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

    private static final DateTimeFormatter CSV_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Максимум загружаем в память для фильтрации
    private static final int MAX_LOAD = 10_000;

    private final AuditEventRepository repo;

    public AuditController(AuditEventRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String type,
                       @RequestParam(required = false) String actor,
                       @RequestParam(required = false) String result,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "200") int limit,
                       Model model) {

        int safeLimit = Math.min(Math.max(limit, 10), 2000);

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
        return "admin-audit";
    }

    @GetMapping("/export.csv")
    public void exportCsv(@RequestParam(required = false) String type,
                          @RequestParam(required = false) String actor,
                          @RequestParam(required = false) String result,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          HttpServletResponse resp) throws Exception {

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

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
