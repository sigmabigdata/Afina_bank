package ru.example.ukep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UkepSignApplication {
    public static void main(String[] args) {
        SpringApplication.run(UkepSignApplication.class, args);
    }
}
