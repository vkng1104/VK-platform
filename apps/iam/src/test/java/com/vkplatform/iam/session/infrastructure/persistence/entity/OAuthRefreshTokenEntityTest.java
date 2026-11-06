package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.OAuthRefreshToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthRefreshTokenEntityTest {
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");

    @Test
    void mapsTheDomainTokenBothWays() {
        OAuthRefreshToken token = token();

        assertThat(new OAuthRefreshTokenEntity(token, new byte[32]).toDomain()).isEqualTo(token);
    }

    @Test
    void appliesTransitionsButNotIdentityChanges() {
        OAuthRefreshToken token = token();
        OAuthRefreshTokenEntity entity = new OAuthRefreshTokenEntity(token, new byte[32]);
        OAuthRefreshToken reused = token.consume(UUID.randomUUID(), NOW.plusSeconds(10))
                .detectReuse(NOW.plusSeconds(20));

        entity.apply(reused);

        assertThat(entity.toDomain()).isEqualTo(reused);
        assertThatThrownBy(() -> entity.apply(OAuthRefreshToken.issue(
                token.id(),
                "authorization-2",
                token.familyId(),
                null,
                NOW,
                NOW.plusSeconds(300)
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesAndDefensivelyCopiesTheTokenDigest() {
        assertThatThrownBy(() -> new OAuthRefreshTokenEntity(token(), new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OAuthRefreshTokenEntity(token(), null))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] digest = new byte[32];
        digest[0] = 7;
        OAuthRefreshTokenEntity entity = new OAuthRefreshTokenEntity(token(), digest);
        digest[0] = 9;
        byte[] returned = entity.tokenDigest();
        returned[0] = 11;

        assertThat(entity.tokenDigest()[0]).isEqualTo((byte) 7);
    }

    private static OAuthRefreshToken token() {
        UUID id = UUID.randomUUID();
        return OAuthRefreshToken.issue(id, "authorization-1", id, null, NOW, NOW.plusSeconds(300));
    }
}
