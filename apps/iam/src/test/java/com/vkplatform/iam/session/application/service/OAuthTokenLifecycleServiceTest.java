package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.SecurityAuditEventType;
import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.application.port.out.OAuthRefreshTokenRepository;
import com.vkplatform.iam.session.application.service.OAuthTokenLifecycleService.AccessTokenWindow;
import com.vkplatform.iam.session.application.service.OAuthTokenLifecycleService.RefreshTokenUse;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.OAuthRefreshToken;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OAuthTokenLifecycleServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-11-05T01:00:00Z");
    private static final Instant NOW = CREATED_AT.plusSeconds(600);
    private static final UUID ACCOUNT_ID = UUID.fromString("5a3c1d2e-7b8f-4c6d-9e0a-1b2c3d4e5f60");
    private static final UUID SESSION_ID = UUID.fromString("6b4d2e3f-8c9a-4d7e-8f1b-2c3d4e5f6a71");
    private static final String CLIENT_ID = "vk-platform-bff";
    private static final String REGISTERED_CLIENT_ID = "registered-client-1";
    private static final String AUTHORIZATION_ID = "authorization-1";
    private static final byte[] PREVIOUS_DIGEST = digest(1);
    private static final byte[] CURRENT_DIGEST = digest(2);

    private AuthenticationSessionRepository sessions;
    private OAuthAuthorizationRepository authorizations;
    private OAuthRefreshTokenRepository refreshTokens;
    private PasswordlessIdentityOperations identities;
    private SecurityAuditOperations audit;
    private IamSecurityTelemetry telemetry;
    private OAuthTokenLifecycleService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AuthenticationSessionRepository.class);
        authorizations = mock(OAuthAuthorizationRepository.class);
        refreshTokens = mock(OAuthRefreshTokenRepository.class);
        identities = mock(PasswordlessIdentityOperations.class);
        audit = mock(SecurityAuditOperations.class);
        telemetry = mock(IamSecurityTelemetry.class);
        service = new OAuthTokenLifecycleService(
                sessions,
                authorizations,
                refreshTokens,
                identities,
                audit,
                telemetry,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );
    }

    @Test
    void accessTokenWindowStartsNowAndLastsTheTtlForAFreshAccountAndLongSession() {
        activeAccount();
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));

        AccessTokenWindow window = service.accessTokenWindow(ACCOUNT_ID, SESSION_ID, CLIENT_ID);

        assertThat(window.notBefore()).isEqualTo(NOW);
        assertThat(window.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        verify(sessions, never()).findLockedById(any());
    }

    @Test
    void accessTokenWindowRespectsTokenNotBeforeAndTheSessionExpiry() {
        when(identities.findActiveAccount(ACCOUNT_ID))
                .thenReturn(Optional.of(new AuthenticatedAccount(ACCOUNT_ID, NOW.plusSeconds(10))));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(AuthenticationSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                CLIENT_ID,
                CREATED_AT,
                NOW.plusSeconds(120)
        )));

        AccessTokenWindow window = service.accessTokenWindow(ACCOUNT_ID, SESSION_ID, CLIENT_ID);

        assertThat(window.notBefore()).isEqualTo(NOW.plusSeconds(10));
        assertThat(window.expiresAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void accessTokenWindowRefusesAnInactiveAccountBeforeReadingTheSession() {
        when(identities.findActiveAccount(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accessTokenWindow(ACCOUNT_ID, SESSION_ID, CLIENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account is unavailable");
        verifyNoInteractions(sessions);
    }

    @Test
    void accessTokenWindowRefusesAMissingOrUnusableSession() {
        activeAccount();
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accessTokenWindow(ACCOUNT_ID, SESSION_ID, CLIENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session is unavailable");

        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));
        assertThatThrownBy(() -> service.accessTokenWindow(ACCOUNT_ID, SESSION_ID, "other-client"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session is unavailable");
    }

    @Test
    void locksAUsableSessionOfAnActiveAccount() {
        activeAccount();
        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(activeSession()));

        assertThat(service.lockUsableSession(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isTrue();
        assertThat(service.lockUsableSession(SESSION_ID, ACCOUNT_ID, "other-client")).isFalse();
        assertThat(service.lockUsableSession(SESSION_ID, UUID.randomUUID(), CLIENT_ID)).isFalse();
    }

    @Test
    void doesNotLockTheSessionOfAnInactiveAccount() {
        when(identities.findActiveAccount(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(service.lockUsableSession(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isFalse();
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectsMissingRevokedAndExpiredSessions() {
        activeAccount();
        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.empty());
        assertThat(service.lockUsableSession(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isFalse();

        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(
                activeSession().revoke(SessionRevokeReason.LOGOUT, NOW)
        ));
        assertThat(service.lockUsableSession(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isFalse();

        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(AuthenticationSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                CLIENT_ID,
                CREATED_AT,
                NOW
        )));
        assertThat(service.lockUsableSession(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isFalse();
    }

    @Test
    void checksSessionUsabilityWithoutLockingOrAnAccountLookup() {
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(activeSession()));

        assertThat(service.isSessionUsable(SESSION_ID, ACCOUNT_ID, CLIENT_ID)).isTrue();
        assertThat(service.isSessionUsable(SESSION_ID, ACCOUNT_ID, "other-client")).isFalse();
        verify(sessions, never()).findLockedById(any());
        verifyNoInteractions(identities);
    }

    @Test
    void reportsUnknownActiveAndExpiredRefreshTokens() {
        when(refreshTokens.findLockedByTokenDigest(CURRENT_DIGEST)).thenReturn(Optional.empty());
        assertThat(service.useRefreshToken(
                CURRENT_DIGEST, SESSION_ID, ACCOUNT_ID, REGISTERED_CLIENT_ID
        )).isEqualTo(RefreshTokenUse.UNKNOWN);

        when(refreshTokens.findLockedByTokenDigest(CURRENT_DIGEST))
                .thenReturn(Optional.of(refreshToken(NOW.plusSeconds(1))));
        assertThat(service.useRefreshToken(
                CURRENT_DIGEST, SESSION_ID, ACCOUNT_ID, REGISTERED_CLIENT_ID
        )).isEqualTo(RefreshTokenUse.ACTIVE);

        when(refreshTokens.findLockedByTokenDigest(CURRENT_DIGEST))
                .thenReturn(Optional.of(refreshToken(NOW)));
        assertThat(service.useRefreshToken(
                CURRENT_DIGEST, SESSION_ID, ACCOUNT_ID, REGISTERED_CLIENT_ID
        )).isEqualTo(RefreshTokenUse.INACTIVE);

        verifyNoInteractions(authorizations);
        verify(refreshTokens, never()).recordReuse(any());
        verify(sessions, never()).recordCompromise(any());
    }

    @Test
    void treatsAConsumedRefreshTokenAsTheftAtOneTimestamp() {
        OAuthRefreshToken consumed = refreshToken(NOW.plusSeconds(3600))
                .consume(UUID.randomUUID(), NOW.minusSeconds(60));
        AuthenticationSession session = activeSession();
        when(refreshTokens.findLockedByTokenDigest(PREVIOUS_DIGEST)).thenReturn(Optional.of(consumed));
        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThat(service.useRefreshToken(
                PREVIOUS_DIGEST, SESSION_ID, ACCOUNT_ID, REGISTERED_CLIENT_ID
        )).isEqualTo(RefreshTokenUse.REUSED);

        InOrder order = inOrder(refreshTokens, authorizations, sessions);
        order.verify(refreshTokens).recordReuse(consumed.detectReuse(NOW));
        order.verify(authorizations).invalidate(
                AUTHORIZATION_ID,
                OAuthAuthorizationInvalidationReason.REFRESH_TOKEN_REUSED,
                NOW
        );
        order.verify(sessions).recordCompromise(session.compromise(NOW));
        ArgumentCaptor<RecordSecurityAuditEvent> event = ArgumentCaptor.forClass(
                RecordSecurityAuditEvent.class
        );
        verify(audit).record(event.capture());
        assertThat(event.getValue().type()).isEqualTo(SecurityAuditEventType.REFRESH_TOKEN_REUSED);
        assertThat(event.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.getValue().targetId()).isEqualTo(SESSION_ID);
        verify(telemetry).recordAfterCommit(
                IamSecurityTelemetry.Operation.REFRESH_REPLAY,
                IamSecurityTelemetry.Outcome.REPLAY,
                0L
        );
    }

    @Test
    void startsAFamilyWithTheFirstRefreshTokenWithoutRefreshingTheSession() {
        service.recordRefreshTokenIssued(
                AUTHORIZATION_ID,
                SESSION_ID,
                ACCOUNT_ID,
                REGISTERED_CLIENT_ID,
                null,
                CURRENT_DIGEST,
                NOW,
                NOW.plusSeconds(3600)
        );

        OAuthRefreshToken root = createdToken();
        assertThat(root.familyId()).isEqualTo(root.id());
        assertThat(root.parentId()).isNull();
        assertThat(root.authorizationId()).isEqualTo(AUTHORIZATION_ID);
        verify(refreshTokens, never()).findLockedByTokenDigest(any());
        verifyNoInteractions(sessions);
    }

    @Test
    void rotationConsumesThePreviousTokenAndRefreshesTheSession() {
        OAuthRefreshToken previous = refreshToken(NOW.plusSeconds(3600));
        AuthenticationSession session = activeSession();
        when(refreshTokens.findLockedByTokenDigest(PREVIOUS_DIGEST)).thenReturn(Optional.of(previous));
        when(sessions.findLockedById(SESSION_ID)).thenReturn(Optional.of(session));

        service.recordRefreshTokenIssued(
                AUTHORIZATION_ID,
                SESSION_ID,
                ACCOUNT_ID,
                REGISTERED_CLIENT_ID,
                PREVIOUS_DIGEST,
                CURRENT_DIGEST,
                NOW,
                NOW.plusSeconds(3600)
        );

        OAuthRefreshToken next = createdToken();
        assertThat(next.familyId()).isEqualTo(previous.familyId());
        assertThat(next.parentId()).isEqualTo(previous.id());
        InOrder order = inOrder(refreshTokens, sessions);
        order.verify(refreshTokens).recordConsumption(previous.consume(next.id(), NOW));
        order.verify(refreshTokens).create(eq(next), eq(CURRENT_DIGEST));
        order.verify(sessions).recordRefresh(session.touch(NOW));
        ArgumentCaptor<RecordSecurityAuditEvent> event = ArgumentCaptor.forClass(
                RecordSecurityAuditEvent.class
        );
        verify(audit).record(event.capture());
        assertThat(event.getValue().type()).isEqualTo(SecurityAuditEventType.REFRESH_TOKEN_ROTATED);
        assertThat(event.getValue().targetId()).isEqualTo(SESSION_ID);
        verify(telemetry).recordAfterCommit(
                IamSecurityTelemetry.Operation.REFRESH_ROTATION,
                IamSecurityTelemetry.Outcome.SUCCESS,
                0L
        );
    }

    @Test
    void rotationFailsWhenThePreviousTokenIsMissingFromTheLedger() {
        when(refreshTokens.findLockedByTokenDigest(PREVIOUS_DIGEST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordRefreshTokenIssued(
                AUTHORIZATION_ID,
                SESSION_ID,
                ACCOUNT_ID,
                REGISTERED_CLIENT_ID,
                PREVIOUS_DIGEST,
                CURRENT_DIGEST,
                NOW,
                NOW.plusSeconds(3600)
        )).isInstanceOf(IllegalStateException.class);
        verify(refreshTokens, never()).create(any(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void invalidatesARemovedAuthorizationAtTheCurrentTime() {
        service.recordAuthorizationRemoved(AUTHORIZATION_ID);

        verify(authorizations).invalidate(
                AUTHORIZATION_ID,
                OAuthAuthorizationInvalidationReason.AUTHORIZATION_REMOVED,
                NOW
        );
    }

    private OAuthRefreshToken createdToken() {
        ArgumentCaptor<OAuthRefreshToken> created = ArgumentCaptor.forClass(OAuthRefreshToken.class);
        verify(refreshTokens).create(created.capture(), eq(CURRENT_DIGEST));
        OAuthRefreshToken token = created.getValue();
        assertThat(token.issuedAt()).isEqualTo(NOW);
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(token.consumedAt()).isNull();
        return token;
    }

    private void activeAccount() {
        when(identities.findActiveAccount(ACCOUNT_ID))
                .thenReturn(Optional.of(new AuthenticatedAccount(ACCOUNT_ID, CREATED_AT)));
    }

    private static AuthenticationSession activeSession() {
        AuthenticationSession session = AuthenticationSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                CLIENT_ID,
                CREATED_AT,
                CREATED_AT.plusSeconds(86_400)
        );
        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        return session;
    }

    private static OAuthRefreshToken refreshToken(Instant expiresAt) {
        UUID id = UUID.randomUUID();
        return OAuthRefreshToken.issue(id, AUTHORIZATION_ID, id, null, CREATED_AT, expiresAt);
    }

    private static byte[] digest(int seed) {
        byte[] digest = new byte[32];
        digest[0] = (byte) seed;
        return digest;
    }
}
