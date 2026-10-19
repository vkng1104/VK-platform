package com.vkplatform.iam.session.infrastructure.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthRefreshTokenEntityTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-10-19T01:00:00Z");

    @Test
    void permitsOneConsumptionThenIdempotentReuseDetection() {
        OAuthRefreshTokenEntity token = token();
        UUID replacement = UUID.randomUUID();

        token.consume(replacement, ISSUED_AT.plusSeconds(1));
        token.detectReuse(ISSUED_AT.plusSeconds(2));
        token.detectReuse(ISSUED_AT.plusSeconds(3));

        assertThat(token.replacementId()).isEqualTo(replacement);
        assertThat(token.consumedAt()).isEqualTo(ISSUED_AT.plusSeconds(1));
        assertThat(token.reuseDetectedAt()).isEqualTo(ISSUED_AT.plusSeconds(2));
    }

    @Test
    void rejectsDuplicateConsumptionInvalidReplacementAndBackwardTime() {
        OAuthRefreshTokenEntity token = token();
        UUID replacement = UUID.randomUUID();

        assertThatThrownBy(() -> token.consume(token.getId(), ISSUED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> token.consume(replacement, ISSUED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        token.consume(replacement, ISSUED_AT.plusSeconds(1));
        assertThatThrownBy(() -> token.consume(UUID.randomUUID(), ISSUED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsReuseBeforeConsumptionOrBeforeTheConsumptionTimestamp() {
        OAuthRefreshTokenEntity token = token();

        assertThatThrownBy(() -> token.detectReuse(ISSUED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        token.consume(UUID.randomUUID(), ISSUED_AT.plusSeconds(2));
        assertThatThrownBy(() -> token.detectReuse(ISSUED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static OAuthRefreshTokenEntity token() {
        return new OAuthRefreshTokenEntity(
                UUID.randomUUID(),
                "authorization-id",
                UUID.randomUUID(),
                null,
                new byte[32],
                ISSUED_AT,
                ISSUED_AT.plusSeconds(3600)
        );
    }
}
