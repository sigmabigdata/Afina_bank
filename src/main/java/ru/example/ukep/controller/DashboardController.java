package ru.example.ukep.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public String dashboard(@AuthenticationPrincipal org.springframework.security.core.userdetails.User principal,
                            Model model) {
        User user = userRepository.findByEmail(principal.getUsername()).orElseThrow();
        model.addAttribute("user", user);
        model.addAttribute("documents", documentService.listForUser(user));
        return "dashboard";
    }
}
