package com.vkplatform.iam.session.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthRefreshTokenTest {
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");
    private static final UUID ID = UUID.fromString("0b5d8f6a-1c2e-4a3b-9d4e-5f6a7b8c9d01");

    @Test
    void issuesAnActiveTokenWithItsLineage() {
        UUID familyId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        OAuthRefreshToken token = OAuthRefreshToken.issue(
                ID,
                "authorization-1",
                familyId,
                parentId,
                NOW,
                NOW.plusSeconds(300)
        );

        assertThat(token.familyId()).isEqualTo(familyId);
        assertThat(token.parentId()).isEqualTo(parentId);
        assertThat(token.isActive(NOW.plusSeconds(299))).isTrue();
        assertThat(token.isActive(NOW.plusSeconds(300))).isFalse();
    }

    @Test
    void consumesOnceAndRecordsIdempotentReuseDetection() {
        UUID replacement = UUID.randomUUID();

        OAuthRefreshToken consumed = token().consume(replacement, NOW.plusSeconds(10));
        OAuthRefreshToken reused = consumed.detectReuse(NOW.plusSeconds(20));

        assertThat(consumed.replacementId()).isEqualTo(replacement);
        assertThat(consumed.consumedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(consumed.isActive(NOW.plusSeconds(11))).isFalse();
        assertThat(reused.reuseDetectedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(reused.detectReuse(NOW.plusSeconds(30))).isSameAs(reused);
    }

    @Test
    void rejectsInvalidTransitions() {
        OAuthRefreshToken token = token();

        assertThatThrownBy(() -> token.detectReuse(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> token.consume(ID, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> token.consume(UUID.randomUUID(), NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        OAuthRefreshToken consumed = token.consume(UUID.randomUUID(), NOW.plusSeconds(10));
        assertThatThrownBy(() -> consumed.consume(UUID.randomUUID(), NOW.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> consumed.detectReuse(NOW.plusSeconds(9)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsStatesTheLedgerTableRejects() {
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> state(" ", NOW.plusSeconds(300), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state("authorization-1", NOW, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state("authorization-1", NOW.plusSeconds(300), ID, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state("authorization-1", NOW.plusSeconds(300), null, other, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state("authorization-1", NOW.plusSeconds(300), null, other, NOW.minusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state("authorization-1", NOW.plusSeconds(300), null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state("authorization-1", NOW.plusSeconds(300), null, other, NOW.plusSeconds(5), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OAuthRefreshToken token() {
        return OAuthRefreshToken.issue(ID, "authorization-1", ID, null, NOW, NOW.plusSeconds(300));
    }

    private static OAuthRefreshToken state(
            String authorizationId,
            Instant expiresAt,
            UUID parentId,
            UUID replacementId,
            Instant consumedAt,
            Instant reuseDetectedAt
    ) {
        return new OAuthRefreshToken(
                ID,
                authorizationId,
                ID,
                parentId,
                NOW,
                expiresAt,
                replacementId,
                consumedAt,
                reuseDetectedAt
        );
    }
}
