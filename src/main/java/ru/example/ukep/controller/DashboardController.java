package ru.example.ukep.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.service.DocumentService;

@Controller
public class DashboardController {

    private final DocumentService documentService;
    private final UserRepository userRepository;

    public DashboardController(DocumentService documentService, UserRepository userRepository) {
        this.documentService = documentService;
        this.userRepository = userRepository;
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication auth, Model model) {
        // Админ не должен видеть клиентский кабинет — перенаправляем
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) return "redirect:/admin";

        User user = userRepository.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("user", user);
        model.addAttribute("documents", documentService.listForUser(user));
        return "dashboard";
    }
}
