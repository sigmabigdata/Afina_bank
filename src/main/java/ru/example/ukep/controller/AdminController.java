package ru.example.ukep.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;

    public AdminController(UserRepository userRepository, DocumentRepository documentRepository) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
    }

    @GetMapping("/login")
    public String login() { return "admin-login"; }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("users", userRepository.findAll());
        model.addAttribute("docsCount", documentRepository.count());
        return "admin";
    }
}
