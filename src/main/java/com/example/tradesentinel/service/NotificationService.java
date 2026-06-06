package com.example.tradesentinel.service;

import com.example.tradesentinel.entity.NotificationEntity;
import com.example.tradesentinel.model.Alert;
import com.example.tradesentinel.model.TriageResult;
import com.example.tradesentinel.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sends notifications via Email and Slack.
 * Falls back to MOCK (log only) when credentials are absent — no code changes needed
 * when real credentials are later provided via env vars.
 */
@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

    private final String emailTo;
    private final String mailUsername;
    private final String slackWebhookUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public NotificationService(
            NotificationRepository notificationRepository,
            JavaMailSender mailSender,
            ObjectMapper objectMapper,
            @Value("${notifications.email.to:compliance@tradesentinel.local}") String emailTo,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${notifications.slack.webhook-url:}") String slackWebhookUrl) {
        this.notificationRepository = notificationRepository;
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.emailTo = emailTo;
        this.mailUsername = mailUsername;
        this.slackWebhookUrl = slackWebhookUrl;
    }

    public void notify(Alert alert, TriageResult triage, int riskScore) {
        String subject = "[Trade Sentinel] %s Alert — %s | %s | Risk Score %d"
                .formatted(alert.severity(), alert.pattern(), alert.traderId(), riskScore);
        String body = buildMessage(alert, triage, riskScore);

        sendEmail(alert.alertId(), subject, body);
        sendSlack(alert.alertId(), subject, body, alert.severity());
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    private void sendEmail(String alertId, String subject, String body) {
        String notifId = "EMAIL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        NotificationEntity entity = buildEntity(notifId, alertId, "EMAIL", emailTo, subject + "\n\n" + body);

        boolean hasCredentials = mailUsername != null && !mailUsername.isBlank();
        if (!hasCredentials) {
            log.info("[MOCK EMAIL] To: {} | Subject: {}", emailTo, subject);
            entity.setStatus("MOCK");
            notificationRepository.save(entity);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(emailTo);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            entity.setStatus("SENT");
            entity.setSentAt(Instant.now());
            notificationRepository.save(entity);
            log.info("Email sent to {} for alert {}", emailTo, alertId);
        } catch (Exception ex) {
            log.warn("Email send failed for alert {}: {}", alertId, ex.getMessage());
            entity.setStatus("FAILED");
            notificationRepository.save(entity);
        }
    }

    // ── Slack ─────────────────────────────────────────────────────────────────

    private void sendSlack(String alertId, String subject, String body, String severity) {
        String notifId = "SLACK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String slackBody = subject + "\n" + body.substring(0, Math.min(300, body.length()));
        NotificationEntity entity = buildEntity(notifId, alertId, "SLACK", "compliance-channel", slackBody);

        boolean hasWebhook = slackWebhookUrl != null && !slackWebhookUrl.isBlank()
                && !slackWebhookUrl.contains("YOUR");
        if (!hasWebhook) {
            log.info("[MOCK SLACK] {}", subject);
            entity.setStatus("MOCK");
            notificationRepository.save(entity);
            return;
        }

        try {
            String emoji = switch (severity) {
                case "CRITICAL" -> ":rotating_light:";
                case "HIGH"     -> ":warning:";
                default         -> ":information_source:";
            };
            Map<String, Object> payload = Map.of(
                    "text", emoji + " *" + subject + "*",
                    "attachments", java.util.List.of(Map.of("text", body, "color",
                            "CRITICAL".equals(severity) ? "#ef4444" : "#f59e0b")));

            HttpRequest request = HttpRequest.newBuilder(URI.create(slackWebhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                entity.setStatus("SENT");
                entity.setSentAt(Instant.now());
            } else {
                entity.setStatus("FAILED");
                log.warn("Slack returned HTTP {} for alert {}", response.statusCode(), alertId);
            }
            notificationRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Slack send failed for alert {}: {}", alertId, ex.getMessage());
            entity.setStatus("FAILED");
            notificationRepository.save(entity);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildMessage(Alert alert, TriageResult triage, int riskScore) {
        return """
                Alert ID   : %s
                Pattern    : %s
                Trader     : %s
                Symbol     : %s
                Severity   : %s
                Risk Score : %d / 100
                Verdict    : %s (%d%% confidence)
                Reason     : %s
                Triage By  : %s
                """.formatted(
                alert.alertId(), alert.pattern(), alert.traderId(), alert.symbol(),
                alert.severity(), riskScore, triage.verdict(), triage.confidence(),
                triage.reason(), triage.source());
    }

    private NotificationEntity buildEntity(String notifId, String alertId,
                                            String channel, String recipient, String message) {
        NotificationEntity entity = new NotificationEntity();
        entity.setNotificationId(notifId);
        entity.setAlertId(alertId);
        entity.setChannel(channel);
        entity.setRecipient(recipient);
        entity.setMessage(message);
        entity.setStatus("PENDING");
        return entity;
    }
}
