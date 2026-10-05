package ru.example.ukep.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** Необязательный бин — в dev-профиле SMTP может быть не настроен. */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.mode:log}")
    private String mode;

    @Value("${app.mail.from:noreply@example.com}")
    private String from;

    @Value("${app.mail.from-name:Афина}")
    private String fromName;

    public EmailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    public void sendLoginLink(String to, String loginUrl) {
        String subject = "Афина · Ссылка для входа";
        String body = """
                Здравствуйте!

                Для входа в личный кабинет перейдите по ссылке:
                %s

                Ссылка действует 10 часов и может быть использована только один раз.
                Если вы не запрашивали вход — просто проигнорируйте это письмо.

                С уважением,
                сервис «Афина»
                """.formatted(loginUrl);

        JavaMailSender sender = mailSenderProvider.getIfAvailable();

        // В dev-режиме или если SMTP не настроен — пишем ссылку в лог
        if (!"smtp".equalsIgnoreCase(mode) || sender == null) {
            log.warn("""

                    ============================================================
                    [DEV-MAIL] Кому : {}
                    [DEV-MAIL] Тема : {}
                    [DEV-MAIL] Ссылка для входа:
                    {}
                    ============================================================
                    """, to, subject, loginUrl);
            return;
        }

        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(msg);
            log.info("Login link email sent to {}", to);
        } catch (Exception e) {
            log.error("Не удалось отправить письмо на {}", to, e);
            // На случай сбоя SMTP — резервно выводим ссылку в лог
            log.warn("[FALLBACK-MAIL] Ссылка для {}: {}", to, loginUrl);
        }
    }
}
