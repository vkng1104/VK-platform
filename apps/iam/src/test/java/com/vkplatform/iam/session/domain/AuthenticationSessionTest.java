package com.vkplatform.iam.session.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-24T01:00:00Z");

    @Test
    void createsAnActiveSessionWithoutRevocationState() {
        AuthenticationSession session = AuthenticationSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );

        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.lastRefreshedAt()).isEqualTo(NOW);
        assertThat(session.compromisedAt()).isNull();
        assertThat(session.revokedAt()).isNull();
        assertThat(session.revokeReason()).isNull();
        assertThat(SessionStatus.values()).containsExactly(
                SessionStatus.ACTIVE,
                SessionStatus.REVOKED,
                SessionStatus.COMPROMISED
        );
    }

    @Test
    void touchesOnlyAnActiveUnexpiredSession() {
        AuthenticationSession active = AuthenticationSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(300)
        );

        AuthenticationSession touched = active.touch(NOW.plusSeconds(30));

        assertThat(touched.lastRefreshedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThatThrownBy(() -> touched.touch(NOW.plusSeconds(300)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> touched.touch(NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compromiseIsTerminalAndPreservesItsFirstTimestamp() {
        UUID accountId = UUID.randomUUID();
        AuthenticationSession active = AuthenticationSession.create(
                UUID.randomUUID(),
                accountId,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(300)
        ).touch(NOW.plusSeconds(10));

        AuthenticationSession compromised = active.compromise(NOW.plusSeconds(20));

        assertThat(compromised.status()).isEqualTo(SessionStatus.COMPROMISED);
        assertThat(compromised.revokedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(compromised.compromisedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(compromised.revokeReason()).isEqualTo(SessionRevokeReason.REFRESH_TOKEN_REUSED);
        assertThat(compromised.canAuthorize(accountId, "vk-platform-bff", NOW.plusSeconds(20))).isFalse();
        assertThat(compromised.compromise(NOW.plusSeconds(30))).isSameAs(compromised);
        assertThat(compromised.revoke(SessionRevokeReason.LOGOUT, NOW.plusSeconds(30)))
                .isSameAs(compromised);
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

    @Test
    void authorizesOnlyTheOwningAccountClientAndActiveLifetime() {
        UUID accountId = UUID.randomUUID();
        AuthenticationSession session = AuthenticationSession.create(
                UUID.randomUUID(),
                accountId,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(300)
        );

        assertThat(session.canAuthorize(accountId, "vk-platform-bff", NOW)).isTrue();
        assertThat(session.canAuthorize(UUID.randomUUID(), "vk-platform-bff", NOW)).isFalse();
        assertThat(session.canAuthorize(accountId, "other-client", NOW)).isFalse();
        assertThat(session.canAuthorize(accountId, "vk-platform-bff", NOW.plusSeconds(300))).isFalse();
    }

    @Test
    void revokesOnceAndCanNoLongerAuthorize() {
        UUID accountId = UUID.randomUUID();
        AuthenticationSession active = AuthenticationSession.create(
                UUID.randomUUID(),
                accountId,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(300)
        );
        Instant revokedAt = NOW.plusSeconds(30);

        AuthenticationSession revoked = active.revoke(SessionRevokeReason.USER_REQUEST, revokedAt);

        assertThat(revoked.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(revokedAt);
        assertThat(revoked.revokeReason()).isEqualTo(SessionRevokeReason.USER_REQUEST);
        assertThat(revoked.canAuthorize(accountId, "vk-platform-bff", revokedAt)).isFalse();
        assertThat(revoked.revoke(SessionRevokeReason.LOGOUT, revokedAt.plusSeconds(1)))
                .isSameAs(revoked);
    }

    @Test
    void rejectsInconsistentRevocationStateAndTimeline() {
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Instant expiresAt = NOW.plusSeconds(300);

        assertThatThrownBy(() -> new AuthenticationSession(
                id,
                accountId,
                "vk-platform-bff",
                SessionStatus.ACTIVE,
                NOW,
                NOW,
                expiresAt,
                NOW.plusSeconds(1),
                null,
                SessionRevokeReason.USER_REQUEST
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticationSession(
                id,
                accountId,
                "vk-platform-bff",
                SessionStatus.REVOKED,
                NOW,
                NOW,
                expiresAt,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticationSession(
                id,
                accountId,
                "vk-platform-bff",
                SessionStatus.REVOKED,
                NOW,
                NOW,
                expiresAt,
                NOW.minusSeconds(1),
                null,
                SessionRevokeReason.USER_REQUEST
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthenticationSession.create(
                id,
                accountId,
                "vk-platform-bff",
                NOW,
                expiresAt
        ).revoke(SessionRevokeReason.USER_REQUEST, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
