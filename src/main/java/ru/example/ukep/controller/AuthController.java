package ru.example.ukep.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import ru.example.ukep.dto.RegistrationForm;
import ru.example.ukep.service.UserService;

@Controller
public class AuthController {

    private final UserService userService;

    @Value("${app.base-url}")
    private String baseUrl;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("form", new RegistrationForm());
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form,
                           BindingResult br, Model model) {
        if (br.hasErrors()) return "register";
        try {
            userService.register(form, baseUrl);
            model.addAttribute("registered", true);
            model.addAttribute("email", form.getEmail());
            return "register";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "register";
        }
    }

    @GetMapping("/confirm")
    public String confirm(@RequestParam("token") String token, Model model) {
        boolean ok = userService.confirm(token);
        model.addAttribute("success", ok);
        return "confirm-result";
    }
}
