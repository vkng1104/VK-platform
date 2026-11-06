package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationSessionValidationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b9a");
    private static final UUID SESSION_ID = UUID.fromString("4d3c2b1a-9f8e-4e7d-8c6b-5a4f3e2d1c0b");
    private static final String CLIENT_ID = "vk-platform-bff";

    private AuthenticationSessionRepository sessions;
    private AuthenticationSessionValidationService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AuthenticationSessionRepository.class);
        service = new AuthenticationSessionValidationService(sessions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsAnActiveUnexpiredSessionOfTheSameAccountAndClient() {
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session(NOW.plusSeconds(60))));

        assertThat(service.isUsable(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isTrue();
        verify(sessions, never()).findLockedById(any());
    }

    @Test
    void rejectsMissingExpiredRevokedOrMismatchedSessions() {
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());
        assertThat(service.isUsable(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isFalse();

        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session(NOW)));
        assertThat(service.isUsable(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isFalse();

        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(
                session(NOW.plusSeconds(60)).revoke(SessionRevokeReason.LOGOUT, NOW)
        ));
        assertThat(service.isUsable(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isFalse();

        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session(NOW.plusSeconds(60))));
        assertThat(service.isUsable(SESSION_ID, UUID.randomUUID(), CLIENT_ID)).isFalse();
        assertThat(service.isUsable(SESSION_ID, ACCOUNT_ID, "other-client")).isFalse();
    }

    private static AuthenticationSession session(Instant expiresAt) {
        return AuthenticationSession.create(SESSION_ID, ACCOUNT_ID, CLIENT_ID, NOW.minusSeconds(60), expiresAt);
    }
}
