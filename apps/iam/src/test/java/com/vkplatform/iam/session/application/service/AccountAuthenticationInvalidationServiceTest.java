package com.vkplatform.iam.session.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountAuthenticationInvalidationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("5a3c1d2e-7b8f-4c6d-9e0a-1b2c3d4e5f60");
    private static final UUID FIRST_SESSION_ID = UUID.fromString("6b4d2e3f-8c9a-4d7e-8f1b-2c3d4e5f6a71");
    private static final UUID SECOND_SESSION_ID = UUID.fromString("7c5e3f4a-9dab-4e8f-9a2c-3d4e5f6a7b82");

    private final Logger logger = (Logger) LoggerFactory.getLogger(
            AccountAuthenticationInvalidationService.class
    );
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private AuthenticationSessionRepository sessions;
    private OAuthAuthorizationRepository authorizations;
    private AccountAuthenticationInvalidationService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AuthenticationSessionRepository.class);
        authorizations = mock(OAuthAuthorizationRepository.class);
        service = new AccountAuthenticationInvalidationService(sessions, authorizations);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void revokesActiveSessionsAndInvalidatesTheirAuthorizationsAtTheEventTime() {
        AuthenticationSession first = activeSession(FIRST_SESSION_ID, NOW.plusSeconds(10));
        AuthenticationSession second = activeSession(SECOND_SESSION_ID, NOW.plusSeconds(20));
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID)).thenReturn(List.of(first, second));

        service.invalidateAccount(ACCOUNT_ID, NOW.plusSeconds(30));

        InOrder order = inOrder(sessions, authorizations);
        order.verify(sessions).findAllLockedByAccountId(ACCOUNT_ID);
        order.verify(sessions).recordRevocation(
                first.revoke(SessionRevokeReason.ACCOUNT_STATE_CHANGED, NOW.plusSeconds(30))
        );
        order.verify(authorizations).invalidateAllForSession(
                FIRST_SESSION_ID,
                OAuthAuthorizationInvalidationReason.ACCOUNT_STATE_CHANGED,
                NOW.plusSeconds(30)
        );
        order.verify(sessions).recordRevocation(
                second.revoke(SessionRevokeReason.ACCOUNT_STATE_CHANGED, NOW.plusSeconds(30))
        );
        order.verify(authorizations).invalidateAllForSession(
                SECOND_SESSION_ID,
                OAuthAuthorizationInvalidationReason.ACCOUNT_STATE_CHANGED,
                NOW.plusSeconds(30)
        );
        assertThat(appender.list).isEmpty();
    }

    @Test
    void usesTheEventTimeWhenItEqualsTheLastRefresh() {
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(activeSession(FIRST_SESSION_ID, NOW.plusSeconds(30))));

        service.invalidateAccount(ACCOUNT_ID, NOW.plusSeconds(30));

        assertThat(recordedRevocation().revokedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(appender.list).isEmpty();
    }

    @Test
    void clampsAnEventTimeEarlierThanTheLastRefreshAndLogsTheAnomaly() {
        Instant lastRefreshedAt = NOW.plusSeconds(30);
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(activeSession(FIRST_SESSION_ID, lastRefreshedAt)));

        service.invalidateAccount(ACCOUNT_ID, NOW.plusSeconds(10));

        AuthenticationSession revoked = recordedRevocation();
        assertThat(revoked.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(lastRefreshedAt);
        assertThat(revoked.revokeReason()).isEqualTo(SessionRevokeReason.ACCOUNT_STATE_CHANGED);
        verify(authorizations).invalidateAllForSession(
                FIRST_SESSION_ID,
                OAuthAuthorizationInvalidationReason.ACCOUNT_STATE_CHANGED,
                lastRefreshedAt
        );
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("Clamped account invalidation", FIRST_SESSION_ID.toString());
        });
    }

    @Test
    void invalidatesAuthorizationsOfAnAlreadyRevokedSessionWithoutRevokingItAgain() {
        AuthenticationSession revoked = activeSession(FIRST_SESSION_ID, NOW.plusSeconds(10))
                .revoke(SessionRevokeReason.LOGOUT, NOW.plusSeconds(20));
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID)).thenReturn(List.of(revoked));

        service.invalidateAccount(ACCOUNT_ID, NOW.plusSeconds(30));

        verify(sessions, never()).recordRevocation(any());
        verify(authorizations).invalidateAllForSession(
                FIRST_SESSION_ID,
                OAuthAuthorizationInvalidationReason.ACCOUNT_STATE_CHANGED,
                NOW.plusSeconds(30)
        );
    }

    @Test
    void writesNothingForAnAccountWithoutSessions() {
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID)).thenReturn(List.of());

        service.invalidateAccount(ACCOUNT_ID, NOW);

        verify(sessions, never()).recordRevocation(any());
        verifyNoInteractions(authorizations);
    }

    @Test
    void rejectsMissingInputsBeforeLockingAnything() {
        assertThatThrownBy(() -> service.invalidateAccount(null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.invalidateAccount(ACCOUNT_ID, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(sessions, authorizations);
    }

    private AuthenticationSession recordedRevocation() {
        ArgumentCaptor<AuthenticationSession> revoked = ArgumentCaptor.forClass(AuthenticationSession.class);
        verify(sessions).recordRevocation(revoked.capture());
        return revoked.getValue();
    }

    private static AuthenticationSession activeSession(UUID sessionId, Instant lastRefreshedAt) {
        return AuthenticationSession.create(
                sessionId,
                ACCOUNT_ID,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        ).touch(lastRefreshedAt);
    }
}
