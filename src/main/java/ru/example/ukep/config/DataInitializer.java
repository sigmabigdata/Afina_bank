package ru.example.ukep.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.example.ukep.service.UserService;

@Configuration
public class DataInitializer {

    @Bean
    public ApplicationRunner initAdmin(UserService userService,
                                       @Value("${admin.email}") String email,
                                       @Value("${admin.password}") String password,
                                       @Value("${admin.full-name}") String fullName) {
        return args -> userService.createAdminIfMissing(email, password, fullName);
    }
}
