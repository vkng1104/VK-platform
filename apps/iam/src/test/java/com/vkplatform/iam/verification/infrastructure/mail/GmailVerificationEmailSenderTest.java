package com.vkplatform.iam.verification.infrastructure.mail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GmailVerificationEmailSenderTest {
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        baseUri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    @AfterEach
    void stopProvider() {
        server.stop(0);
    }

    @Test
    void exchangesTheRefreshTokenAndSendsTheRenderedMultipartMessage() throws Exception {
        AtomicReference<String> tokenForm = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<byte[]> sendBody = new AtomicReference<>();
        server.createContext("/token", exchange -> {
            tokenForm.set(readBody(exchange));
            respond(exchange, 200, "{\"access_token\":\"provider-access-token\"}");
        });
        server.createContext("/send", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sendBody.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "{}");
        });

        sender("client+id", "client-secret", "refresh/token").send(
                new EmailAddress("reader@example.com"),
                new VerificationCode("012345"),
                Instant.parse("2026-10-09T03:05:00Z")
        );

        assertThat(tokenForm.get())
                .contains("client_id=client%2Bid")
                .contains("client_secret=client-secret")
                .contains("refresh_token=refresh%2Ftoken")
                .contains("grant_type=refresh_token");
        assertThat(authorization.get()).isEqualTo("Bearer provider-access-token");

        JsonNode payload = objectMapper.readTree(sendBody.get());
        String message = new String(
                Base64.getUrlDecoder().decode(payload.path("raw").textValue()),
                StandardCharsets.UTF_8
        );
        assertThat(message)
                .contains("From: sender@example.com")
                .contains("To: reader@example.com")
                .contains("Subject: Your VK Platform verification code")
                .contains("Your VK Platform verification code is 012345.")
                .contains("This code expires at 03:05 UTC.")
                .contains("<p style=\"margin:24px 0;font-size:32px;font-weight:700;letter-spacing:8px\">012345</p>")
                .doesNotContain("{{CODE}}")
                .doesNotContain("{{EXPIRES_AT}}");
    }

    @Test
    void providerFailuresDoNotExposeResponseBodiesOrCredentials() {
        server.createContext("/token", exchange -> respond(
                exchange,
                400,
                "provider-secret-response-body"
        ));
        String clientSecret = "client-secret-that-must-not-leak";
        String refreshToken = "refresh-token-that-must-not-leak";

        assertThatThrownBy(() -> sender("client", clientSecret, refreshToken).send(
                new EmailAddress("reader@example.com"),
                new VerificationCode("012345"),
                Instant.parse("2026-10-09T03:05:00Z")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Gmail authentication failed with status 400")
                .hasMessageNotContaining("provider-secret-response-body")
                .hasMessageNotContaining(clientSecret)
                .hasMessageNotContaining(refreshToken);
    }

    @Test
    void rejectsMailboxHeaderInjectionBeforeCallingTheProvider() {
        assertThatThrownBy(() -> new GmailVerificationEmailSender(
                HttpClient.newHttpClient(),
                objectMapper,
                "sender@example.com\r\nBcc: attacker@example.com",
                "client",
                "secret",
                "refresh",
                baseUri.resolve("/token"),
                baseUri.resolve("/send")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mailbox is invalid");
    }

    private GmailVerificationEmailSender sender(String clientId, String clientSecret, String refreshToken) {
        return new GmailVerificationEmailSender(
                HttpClient.newBuilder().build(),
                objectMapper,
                "sender@example.com",
                clientId,
                clientSecret,
                refreshToken,
                baseUri.resolve("/token"),
                baseUri.resolve("/send")
        );
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
