package com.vkplatform.iam.session.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-24T01:00:00Z");

    @Test
    void createsOnlyTheActiveStateNeededByGrantExchange() {
        AuthenticationSession session = AuthenticationSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );

        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(SessionStatus.values()).containsExactly(SessionStatus.ACTIVE);
    }

    @Test
    void rejectsInvalidClientAndExpiryState() {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() -> AuthenticationSession.create(
                id, accountId, " ", NOW, NOW.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthenticationSession.create(
                id, accountId, " vk-platform-bff", NOW, NOW.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthenticationSession.create(
                id, accountId, "vk-platform-bff", NOW, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
