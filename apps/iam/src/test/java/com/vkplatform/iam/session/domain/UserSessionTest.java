package com.vkplatform.iam.session.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-11T06:00:00Z");

    @Test
    void followsTheActiveTouchedRevokedLifecycle() {
        UserSession active = UserSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );

        assertThat(active.canUse("vk-platform-bff", NOW.plusSeconds(1))).isTrue();
        assertThat(active.canUse("another-client", NOW.plusSeconds(1))).isFalse();

        UserSession touched = active.touch(NOW.plusSeconds(10));
        UserSession revoked = touched.revoke(SessionRevokeReason.LOGOUT, NOW.plusSeconds(20));

        assertThat(touched.lastSeenAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(revoked.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(revoked.revokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
        assertThat(revoked.canUse("vk-platform-bff", NOW.plusSeconds(21))).isFalse();
        assertThat(revoked.revoke(SessionRevokeReason.USER_REQUEST, NOW.plusSeconds(30)))
                .isSameAs(revoked);
    }

    @Test
    void marksRefreshReplayAsACompromisedTerminalState() {
        UserSession active = UserSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );

        UserSession compromised = active.compromise(NOW.plusSeconds(15));

        assertThat(compromised.status()).isEqualTo(SessionStatus.COMPROMISED);
        assertThat(compromised.revokedAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(compromised.compromisedAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(compromised.revokeReason()).isEqualTo(SessionRevokeReason.REFRESH_TOKEN_REUSED);
    }

    @Test
    void rejectsInvalidTimelinesAndExpiredUse() {
        UserSession active = UserSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(30)
        );

        assertThat(active.canUse("vk-platform-bff", NOW.plusSeconds(30))).isFalse();
        assertThatThrownBy(() -> active.touch(NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                " ",
                NOW,
                NOW.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
