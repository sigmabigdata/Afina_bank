package ru.example.ukep.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.mode:log}")
    private String mode;

    @Value("${app.mail.from:noreply@example.com}")
    private String from;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendConfirmation(String to, String confirmUrl) {
        String subject = "Подтверждение регистрации";
        String body = """
                Здравствуйте!
                
                Для подтверждения регистрации перейдите по ссылке:
                %s
                
                Ссылка действует 24 часа.
                """.formatted(confirmUrl);

        if ("smtp".equalsIgnoreCase(mode)) {
            try {
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
                helper.setFrom(from);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(body, false);
                mailSender.send(msg);
                log.info("Confirmation email sent to {}", to);
            } catch (Exception e) {
                log.error("Failed to send email to {}", to, e);
            }
        } else {
            // dev-режим: пишем ссылку в лог
            log.warn("""
                    
                    ============================================================
                    [DEV-MAIL] Получатель : {}
                    [DEV-MAIL] Тема       : {}
                    [DEV-MAIL] Ссылка     : {}
                    ============================================================
                    """, to, subject, confirmUrl);
        }
    }
}
