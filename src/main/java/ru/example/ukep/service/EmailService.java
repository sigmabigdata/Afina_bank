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

    /**
     * Алерт мониторинга: сервис недоступен.
     */
    public void sendAlert(String to, int failCount, String reason) {
        String subject = "Афина · Сервис недоступен";
        String body = """
                Обнаружена проблема с сервисом.

                Причина: %s
                Сбоев подряд: %d

                Проверьте сервер:
                  ssh afina-vps
                  cd /opt/afina
                  docker compose -f docker-compose-prod.yml --env-file .env.prod ps
                  docker compose -f docker-compose-prod.yml --env-file .env.prod logs app --tail 50
                """.formatted(reason, failCount);

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (!"smtp".equalsIgnoreCase(mode) || sender == null) {
            log.warn("[DEV-ALERT] to={}, subject={}, body=\n{}", to, subject, body);
            return;
        }

        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(msg);
            log.info("Alert email sent to {}", to);
        } catch (Exception e) {
            log.error("Не удалось отправить alert на {}", to, e);
            throw new RuntimeException("SMTP: " + e.getMessage(), e);
        }
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
