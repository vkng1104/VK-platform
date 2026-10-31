package com.vkplatform.iam.session.infrastructure.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthRefreshTokenEntityTest {
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");

    @Test
    void consumesOnceAndRecordsIdempotentReuseDetection() {
        OAuthRefreshTokenEntity token = token();
        UUID replacement = UUID.randomUUID();

        token.consume(replacement, NOW.plusSeconds(10));
        token.detectReuse(NOW.plusSeconds(20));
        token.detectReuse(NOW.plusSeconds(30));

        assertThat(token.replacementId()).isEqualTo(replacement);
        assertThat(token.consumedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(token.reuseDetectedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(token.isActive(NOW.plusSeconds(20))).isFalse();
    }

    @Test
    void rejectsInvalidLineageTransitions() {
        OAuthRefreshTokenEntity token = token();

        assertThatThrownBy(() -> token.detectReuse(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> token.consume(token.getId(), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        token.consume(UUID.randomUUID(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> token.consume(UUID.randomUUID(), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void defensivelyCopiesTheTokenDigest() {
        byte[] digest = new byte[32];
        digest[0] = 7;
        OAuthRefreshTokenEntity token = new OAuthRefreshTokenEntity(
                UUID.randomUUID(),
                "authorization-1",
                UUID.randomUUID(),
                null,
                digest,
                NOW,
                NOW.plusSeconds(300)
        );

        digest[0] = 9;
        byte[] returned = token.tokenDigest();
        returned[0] = 11;

        assertThat(token.tokenDigest()[0]).isEqualTo((byte) 7);
    }

    private static OAuthRefreshTokenEntity token() {
        return new OAuthRefreshTokenEntity(
                UUID.randomUUID(),
                "authorization-1",
                UUID.randomUUID(),
                null,
                new byte[32],
                NOW,
                NOW.plusSeconds(300)
        );
    }
}
