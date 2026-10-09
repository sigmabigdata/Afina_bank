package ru.example.ukep.service;

/**
 * Событие аудита. Передаётся в {@link AuditEventWriter#write(AuditEntry)}.
 *
 * Вынесено в record, чтобы не таскать 8 строковых параметров через API
 * (Sonar java:S107 — "Method has 8 parameters").
 */
public record AuditEntry(
        String type,
        String result,
        String actorEmail,
        String actorRole,
        String targetType,
        String targetId,
        String targetInfo,
        String details
) {}
