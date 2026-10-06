package ru.example.ukep.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.service.CrlDownloader;
import ru.example.ukep.service.CrlService;

import java.util.HashSet;

@Controller
@RequestMapping("/admin/crl")
public class CrlController {

    private static final Logger log = LoggerFactory.getLogger(CrlController.class);

    private final CrlService crlService;

    public CrlController(CrlService crlService) {
        this.crlService = crlService;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("crls", crlService.listAll());
        model.addAttribute("crlsDir", crlService.getCrlsDir().toString());
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
}
