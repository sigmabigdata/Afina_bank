package ru.example.ukep.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.EmailService;
import ru.example.ukep.service.UserService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final UserService userService;
    private final EmailService emailService;

    @org.springframework.beans.factory.annotation.Value("${app.base-url}")
    private String baseUrl;

    public AdminController(UserRepository userRepository,
                           DocumentRepository documentRepository,
                           DocumentService documentService,
                           UserService userService,
                           EmailService emailService) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.userService = userService;
        this.emailService = emailService;
    }

    // ==================== ДАШБОРД ====================
    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("usersTotal", userRepository.count());
        model.addAttribute("usersActive", userRepository.countByEnabledTrue());
        model.addAttribute("usersPending", userRepository.countByEnabledFalse());
        model.addAttribute("docsTotal", documentRepository.count());
        model.addAttribute("docsSigned", documentRepository.countBySignedTrue());
        model.addAttribute("docsUnsigned", documentRepository.countBySignedFalse());
        model.addAttribute("recentDocs",
                documentRepository.findAllWithOwner().stream().limit(5).toList());
        return "admin";
    }

    // ==================== КЛИЕНТЫ ====================
    @GetMapping("/users")
    public String users(@RequestParam(required = false) String status,
                        @RequestParam(required = false) String q,
                        Model model) {
        Boolean ef = null;
        if ("active".equalsIgnoreCase(status)) ef = true;
        else if ("pending".equalsIgnoreCase(status)) ef = false;

        String qNorm = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        String pattern = (qNorm == null) ? null : "%" + qNorm + "%";

        model.addAttribute("users", userRepository.searchForAdmin(ef, null, pattern));
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("q", qNorm == null ? "" : qNorm);
        return "admin-users";
    }

    @GetMapping("/users/{id}")
    public String userCard(@PathVariable Long id, Model model) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        model.addAttribute("user", user);
        model.addAttribute("documents",
                documentRepository.findAllByOwnerOrderByUploadedAtDesc(user));
        model.addAttribute("docsTotal", documentRepository.countByOwner(user));
        model.addAttribute("docsSigned", documentRepository.countByOwnerAndSignedTrue(user));
        return "admin-user-card";
    }

    @PostMapping("/users/{id}/send-login-link")
    public String sendLoginLink(@PathVariable Long id, RedirectAttributes ra) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        if (u.getRole() == Role.ROLE_ADMIN) {
            ra.addFlashAttribute("err", "Администратор входит по сертификату");
            return "redirect:/admin/users/" + id;
        }
        if (!u.isEnabled()) {
            ra.addFlashAttribute("err", "Клиент заблокирован");
            return "redirect:/admin/users/" + id;
        }
        String link = userService.generateLoginLink(u.getEmail(), baseUrl);
        if (link == null) {
            ra.addFlashAttribute("err", "Не удалось создать ссылку");
        } else {
            emailService.sendLoginLink(u.getEmail(), link);
            ra.addFlashAttribute("ok", "Ссылка отправлена на " + u.getEmail());
        }
        return "redirect:/admin/users/" + id;
    }

    // ==================== CRUD ====================
    @GetMapping("/users/new")
    public String newUserForm(Model model) {
        model.addAttribute("mode", "create");
        return "admin-user-edit";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam String email,
                             @RequestParam(required = false) String fullName,
                             @RequestParam(required = false) String phone,
                             @RequestParam(defaultValue = "false") boolean enabled,
                             RedirectAttributes ra) {
        try {
            User u = userService.adminCreate(email, fullName, phone, Role.ROLE_USER, enabled);
            ra.addFlashAttribute("ok", "Клиент создан: " + u.getEmail());
            return "redirect:/admin/users/" + u.getId();
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/admin/users/new";
        }
    }

    @GetMapping("/users/{id}/edit")
    public String editUserForm(@PathVariable Long id, Model model) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        model.addAttribute("mode", "edit");
        model.addAttribute("user", user);
        return "admin-user-edit";
    }

    @PostMapping("/users/{id}")
    public String updateUser(@PathVariable Long id,
                             @RequestParam String email,
                             @RequestParam(required = false) String fullName,
                             @RequestParam(required = false) String phone,
                             @RequestParam(defaultValue = "false") boolean enabled,
                             RedirectAttributes ra) {
        try {
            userService.adminUpdate(id, email, fullName, phone, Role.ROLE_USER, enabled);
            ra.addFlashAttribute("ok", "Сохранено");
            return "redirect:/admin/users/" + id;
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/admin/users/" + id + "/edit";
        }
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id,
                             java.security.Principal principal,
                             RedirectAttributes ra) {
        try {
            userService.adminDelete(id, principal.getName());
            ra.addFlashAttribute("ok", "Пользователь удалён");
            return "redirect:/admin/users";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/admin/users/" + id;
        }
    }

    @PostMapping("/users/{id}/toggle")
    public String toggleUser(@PathVariable Long id,
                             @RequestParam(required = false) String back,
                             RedirectAttributes ra) {
        try {
            userService.adminToggle(id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "card".equals(back)
                ? "redirect:/admin/users/" + id
                : "redirect:/admin/users";
    }

    // ==================== ДОКУМЕНТЫ ====================
    @GetMapping("/documents/{id}/view")
    public ResponseEntity<Resource> viewDocument(@PathVariable Long id) throws IOException {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        Resource r = documentService.loadAsResource(doc);
        MediaType mt = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" +
                        URLEncoder.encode(doc.getOriginalName(), StandardCharsets.UTF_8))
                .body(r);
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id) throws IOException {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        Resource r = documentService.loadAsResource(doc);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(doc.getOriginalName(), StandardCharsets.UTF_8))
                .body(r);
    }

    @GetMapping("/documents/{id}/signature/download")
    public ResponseEntity<byte[]> downloadSignature(@PathVariable Long id) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Документ не найден"));
        if (!doc.isSigned() || doc.getSignatureBase64() == null) return ResponseEntity.notFound().build();
        byte[] sig = Base64.getDecoder().decode(doc.getSignatureBase64().replaceAll("\\s+", ""));
        String n = doc.getOriginalName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(n + ".sig", StandardCharsets.UTF_8))
                .body(sig);
    }

    @GetMapping("/users/{id}/signatures.zip")
    public ResponseEntity<byte[]> downloadAllSignatures(@PathVariable Long id) throws IOException {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        List<Document> docs = documentRepository.findAllByOwnerOrderByUploadedAtDesc(user)
                .stream().filter(Document::isSigned).toList();
        if (docs.isEmpty()) return ResponseEntity.notFound().build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (Document d : docs) {
                if (d.getSignatureBase64() == null) continue;
                byte[] sig = Base64.getDecoder().decode(d.getSignatureBase64().replaceAll("\\s+", ""));
                String base = stripExtension(d.getOriginalName());
                String entry = uniqueEntryName(base + ".sig", usedNames);
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(sig);
                zip.closeEntry();
            }
        }
        String zn = "signatures_" + user.getId() + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        URLEncoder.encode(zn, StandardCharsets.UTF_8))
                .body(baos.toByteArray());
    }

    private static String stripExtension(String name) {
        if (name == null) return "file";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String uniqueEntryName(String desired, Set<String> used) {
        if (used.add(desired)) return desired;
        int dot = desired.lastIndexOf('.');
        String base = dot > 0 ? desired.substring(0, dot) : desired;
        String ext = dot > 0 ? desired.substring(dot) : "";
        int i = 1;
        String candidate;
        do {
            candidate = base + "_" + i + ext;
            i++;
        } while (!used.add(candidate));
        return candidate;
    }
}
