package com.vkplatform.iam.verification.infrastructure.mail;

import com.vkplatform.iam.verification.application.port.out.VerificationEmailSender;
import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationCode;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class GmailVerificationEmailSender implements VerificationEmailSender {
    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    private static final URI SEND_ENDPOINT = URI.create(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
    );
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter
            .ofPattern("HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final String fromAddress;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private final String subjectTemplate;
    private final String textTemplate;
    private final String htmlTemplate;

    public GmailVerificationEmailSender(
            HttpClient client,
            ObjectMapper objectMapper,
            String fromAddress,
            String clientId,
            String clientSecret,
            String refreshToken
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.fromAddress = exactMailbox(fromAddress);
        this.clientId = requireValue(clientId, "Gmail client ID");
        this.clientSecret = requireValue(clientSecret, "Gmail client secret");
        this.refreshToken = requireValue(refreshToken, "Gmail refresh token");
        this.subjectTemplate = loadTemplate("mail/verification-code.subject.txt");
        this.textTemplate = loadTemplate("mail/verification-code.txt");
        this.htmlTemplate = loadTemplate("mail/verification-code.html");
    }

    @Override
    public void send(EmailAddress recipient, VerificationCode code, Instant expiresAt) {
        String to = exactMailbox(recipient.value());
        try {
            String accessToken = fetchAccessToken();
            String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    buildMessage(to, code.value(), expiresAt).getBytes(StandardCharsets.UTF_8)
            );
            byte[] payload = objectMapper.writeValueAsBytes(Map.of("raw", raw));
            HttpRequest request = HttpRequest.newBuilder(SEND_ENDPOINT)
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Gmail rejected the message with status " + response.statusCode());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Gmail request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gmail request was interrupted", exception);
        }
    }

    private String fetchAccessToken() throws IOException, InterruptedException {
        String form = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&refresh_token=" + encode(refreshToken)
                + "&grant_type=refresh_token";
        HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Gmail authentication failed with status " + response.statusCode());
        }
        JsonNode token = objectMapper.readTree(response.body()).path("access_token");
        if (!token.isTextual() || token.textValue().isBlank()) {
            throw new IllegalStateException("Gmail authentication response did not include an access token");
        }
        return token.textValue();
    }

    private String buildMessage(String recipient, String code, Instant expiresAt) {
        String expires = EXPIRY_FORMAT.format(expiresAt);
        String subject = render(subjectTemplate, code, expires).strip();
        if (subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalStateException("verification email subject is invalid");
        }
        String boundary = "vk-platform-" + UUID.randomUUID();
        return "From: " + fromAddress + "\r\n"
                + "To: " + recipient + "\r\n"
                + "Subject: " + subject + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"" + boundary + "\"\r\n\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + render(textTemplate, code, expires) + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
                + render(htmlTemplate, code, expires) + "\r\n"
                + "--" + boundary + "--\r\n";
    }

    private static String render(String template, String code, String expires) {
        return template.replace("{{CODE}}", code).replace("{{EXPIRES_AT}}", expires);
    }

    private static String loadTemplate(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("could not load email template " + path, exception);
        }
    }

    private static String exactMailbox(String value) {
        String mailbox = requireValue(value, "mailbox");
        if (mailbox.contains("\r") || mailbox.contains("\n") || mailbox.indexOf('@') <= 0
                || mailbox.lastIndexOf('@') != mailbox.indexOf('@')) {
            throw new IllegalArgumentException("mailbox is invalid");
        }
        return mailbox;
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
