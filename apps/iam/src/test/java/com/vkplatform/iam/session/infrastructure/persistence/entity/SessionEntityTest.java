package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.UserSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionEntityTest {
    private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");

    @Test
    void mapsMutableLifecycleStateWithoutChangingIdentity() {
        UserSession active = session(UUID.randomUUID(), UUID.randomUUID(), "vk-platform-bff", NOW.plusSeconds(3600));
        SessionEntity entity = new SessionEntity(active);

        entity.apply(active.touch(NOW.plusSeconds(30)).revoke(SessionRevokeReason.LOGOUT, NOW.plusSeconds(60)));

        UserSession mapped = entity.toDomain();
        assertThat(mapped.id()).isEqualTo(active.id());
        assertThat(mapped.accountId()).isEqualTo(active.accountId());
        assertThat(mapped.lastRefreshedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(mapped.revokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
    }

    @Test
    void rejectsChangesToEveryImmutableSessionField() {
        UserSession active = session(UUID.randomUUID(), UUID.randomUUID(), "vk-platform-bff", NOW.plusSeconds(3600));
        SessionEntity entity = new SessionEntity(active);

        assertThatThrownBy(() -> entity.apply(session(
                UUID.randomUUID(), active.accountId(), active.clientId(), active.expiresAt()
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity.apply(session(
                active.id(), UUID.randomUUID(), active.clientId(), active.expiresAt()
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity.apply(session(
                active.id(), active.accountId(), "other-client", active.expiresAt()
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity.apply(session(
                active.id(), active.accountId(), active.clientId(), active.expiresAt().plusSeconds(1)
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity.apply(new UserSession(
                active.id(),
                active.accountId(),
                active.clientId(),
                active.status(),
                NOW.plusSeconds(1),
                NOW.plusSeconds(1),
                active.expiresAt(),
                null,
                null,
                null
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    private static UserSession session(UUID id, UUID accountId, String clientId, Instant expiresAt) {
        return UserSession.create(id, accountId, clientId, NOW, expiresAt);
    }
}
