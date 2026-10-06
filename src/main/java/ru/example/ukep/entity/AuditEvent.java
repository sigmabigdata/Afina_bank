package ru.example.ukep.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime = Instant.now();

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "actor_email", length = 500)
    private String actorEmail;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id", length = 50)
    private String targetId;

    @Column(name = "target_info", length = 500)
    private String targetInfo;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    // getters/setters
    public Long getId() { return id; }
    public Instant getEventTime() { return eventTime; }
    public String getEventType() { return eventType; }
    public String getResult() { return result; }
    public String getActorEmail() { return actorEmail; }
    public String getActorRole() { return actorRole; }
    public String getActorIp() { return actorIp; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getTargetInfo() { return targetInfo; }
    public String getDetails() { return details; }
    public String getUserAgent() { return userAgent; }

    public void setId(Long id) { this.id = id; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public void setResult(String result) { this.result = result; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public void setActorIp(String actorIp) { this.actorIp = actorIp; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public void setTargetInfo(String targetInfo) { this.targetInfo = targetInfo; }
    public void setDetails(String details) { this.details = details; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
