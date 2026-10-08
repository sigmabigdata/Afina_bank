package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.service.CrlDownloader;
import ru.example.ukep.service.CrlRefreshService;
import ru.example.ukep.service.CrlService;

import java.util.HashSet;
import java.util.List;

@Controller
@RequestMapping("/admin/crl")
public class CrlController {

    private static final Logger log = LoggerFactory.getLogger(CrlController.class);

    private final CrlService crlService;
    private final CrlRefreshService refreshService;

    public CrlController(CrlService crlService, CrlRefreshService refreshService) {
        this.crlService = crlService;
        this.refreshService = refreshService;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("crls", crlService.listAll());
        model.addAttribute("crlsDir", crlService.getCrlsDir().toString());
        long totalSize = crlService.getTotalSize();
        model.addAttribute("totalSize", totalSize);
        model.addAttribute("totalSizePretty", CrlService.prettySize(totalSize));
        model.addAttribute("totalCount", crlService.getCount());
        return "admin-crl";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("err", "Файл пустой");
            return "redirect:/admin/crl";
        }
        try {
            crlService.saveManual(file.getOriginalFilename(), file.getBytes());
            ra.addFlashAttribute("ok", "CRL загружен: " + file.getOriginalFilename()
                    + " (" + CrlService.prettySize(file.getSize()) + ")");
        } catch (Exception e) {
            log.error("CRL upload failed", e);
            ra.addFlashAttribute("err", "Не удалось загрузить: " + e.getMessage());
        }
        return "redirect:/admin/crl";
    }

    @PostMapping("/{name}/delete")
    public String delete(@PathVariable String name, RedirectAttributes ra) {
        try {
            crlService.delete(name);
            ra.addFlashAttribute("ok", "Удалён: " + name);
        } catch (Exception e) {
            ra.addFlashAttribute("err", "Ошибка удаления: " + e.getMessage());
        }
        return "redirect:/admin/crl";
    }

    /** Обновить все CRL сейчас (ручной запуск CrlRefreshService). */
    @PostMapping("/refresh-all")
    public String refreshAll(RedirectAttributes ra) {
        try {
            long t0 = System.currentTimeMillis();
            refreshService.refreshAll();
            long dt = System.currentTimeMillis() - t0;
            ra.addFlashAttribute("ok", "Обновление завершено за " + dt + " мс. Смотри лог app.");
        } catch (Exception e) {
            log.error("CRL refresh-all failed", e);
            ra.addFlashAttribute("err", "Ошибка обновления: " + e.getMessage());
        }
        return "redirect:/admin/crl";
    }

    /** Удалить выбранные CRL по чекбоксам. */
    @PostMapping("/delete-selected")
    public String deleteSelected(@RequestParam(required = false) List<String> names,
                                 RedirectAttributes ra) {
        if (names == null || names.isEmpty()) {
            ra.addFlashAttribute("err", "Ничего не выбрано");
            return "redirect:/admin/crl";
        }
        int n = crlService.deleteMany(names);
        ra.addFlashAttribute("ok", "Удалено: " + n + " из " + names.size());
        return "redirect:/admin/crl";
    }

    /** Удалить все auto-*.crl. */
    @PostMapping("/delete-all-auto")
    public String deleteAllAuto(RedirectAttributes ra) {
        int n = crlService.deleteAllAuto();
        ra.addFlashAttribute("ok", "Удалено auto-*.crl: " + n);
        return "redirect:/admin/crl";
    }
}
