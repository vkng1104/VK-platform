package com.vkplatform.iam.session;

import com.vkplatform.iam.session.infrastructure.config.SessionProperties;

import java.net.URI;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;

public final class SessionTestFixtures {
    private SessionTestFixtures() {
    }

    public static SessionProperties properties() {
        return properties(null);
    }

    public static SessionProperties properties(KeyPair signingKey) {
        return properties(signingKey, "b".repeat(32), "s".repeat(32));
    }

    public static SessionProperties properties(String bffSecret, String serviceSecret) {
        return properties(null, bffSecret, serviceSecret);
    }

    private static SessionProperties properties(
            KeyPair signingKey,
            String bffSecret,
            String serviceSecret
    ) {
        return new SessionProperties(
                true,
                URI.create("https://iam.example.test"),
                "vk-platform-api",
                "vk-platform-web",
                "vk-platform-bff",
                Duration.ofMinutes(5),
                Duration.ofDays(30),
                Duration.ofDays(30),
                URI.create("https://web.example.test/sign-in"),
                URI.create("https://web.example.test"),
                "bootstrap-key",
                signingKey == null ? "" : Base64.getEncoder().encodeToString(signingKey.getPrivate().getEncoded()),
                signingKey == null ? "" : Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(new byte[32]),
                "v1",
                "",
                bffSecret,
                URI.create("https://web.example.test/api/auth/callback"),
                "vk-platform-api",
                serviceSecret,
                "vk-platform-iam",
                "VK_IAM_SESSION",
                true,
                true,
                Duration.ofDays(30),
                Duration.ofDays(30),
                Duration.ofDays(90),
                100,
                "",
                "test-operator",
                "test-operation",
                "",
                "",
                "",
                null,
                100
        );
    }
}
