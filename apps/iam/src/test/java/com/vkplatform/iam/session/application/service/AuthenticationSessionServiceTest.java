package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.command.RevokeAllAuthenticationSessions;
import com.vkplatform.iam.session.api.command.RevokeAuthenticationSession;
import com.vkplatform.iam.session.api.result.AuthenticationSessionPage;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticationSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T02:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("6bc136c5-33fe-4314-821d-ae7a3d791a20");
    private static final String GRANT = "A".repeat(43);

    private PasswordlessIdentityOperations identities;
    private AuthenticationSessionRepository sessions;
    private OAuthAuthorizationRepository authorizations;
    private AuthenticationSessionService service;

    @BeforeEach
    void setUp() {
        identities = mock(PasswordlessIdentityOperations.class);
        sessions = mock(AuthenticationSessionRepository.class);
        authorizations = mock(OAuthAuthorizationRepository.class);
        service = new AuthenticationSessionService(
                identities,
                sessions,
                authorizations,
                new SessionPolicy("vk-platform-bff", Duration.ofDays(30)),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void consumesTheGrantAndCreatesOneActiveBffSession() {
        when(identities.consumeSignInGrant(any()))
                .thenReturn(new AuthenticatedAccount(ACCOUNT_ID, NOW.minusSeconds(1)));

        EstablishedAuthenticationSession established = service.exchange(new ExchangeAuthenticationGrant(
                GRANT,
                "vk-platform-web",
                "request-1"
        ));

        ArgumentCaptor<ConsumeSignInGrant> consumed = ArgumentCaptor.forClass(ConsumeSignInGrant.class);
        verify(identities).consumeSignInGrant(consumed.capture());
        assertThat(consumed.getValue()).isEqualTo(new ConsumeSignInGrant(
                GRANT,
                "vk-platform-web",
                "request-1"
        ));

        ArgumentCaptor<AuthenticationSession> created = ArgumentCaptor.forClass(AuthenticationSession.class);
        verify(sessions).create(created.capture());
        assertThat(created.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(created.getValue().clientId()).isEqualTo("vk-platform-bff");
        assertThat(created.getValue().createdAt()).isEqualTo(NOW);
        assertThat(created.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(established.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(established.session()).isEqualTo(created.getValue());
    }

    @Test
    void doesNotCreateASessionWhenGrantConsumptionFails() {
        when(identities.consumeSignInGrant(any())).thenThrow(IdentityFailure.invalidGrant());

        assertThatThrownBy(() -> service.exchange(new ExchangeAuthenticationGrant(
                GRANT,
                "vk-platform-web",
                "request-2"
        ))).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT));

        verifyNoInteractions(sessions);
    }

    @Test
    void readsAndPagesOnlyFromAUsableCurrentSession() {
        UUID currentId = UUID.randomUUID();
        AuthenticationSession current = session(currentId, NOW.minusSeconds(60));
        AuthenticationSession older = session(UUID.randomUUID(), NOW.minusSeconds(120));
        when(sessions.findOwned(ACCOUNT_ID, currentId)).thenReturn(Optional.of(current));
        when(sessions.findActiveByAccount(ACCOUNT_ID, NOW, 1, 20))
                .thenReturn(new AuthenticationSessionRepository.SessionPage(
                        List.of(older), 1, 20, 21, 2
                ));

        assertThat(service.current(ACCOUNT_ID, currentId).id()).isEqualTo(currentId);
        AuthenticationSessionPage page = service.sessions(ACCOUNT_ID, currentId, 1, 20);

        assertThat(page.sessions()).extracting("id").containsExactly(older.id());
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(21);
    }

    @Test
    void rejectsInvalidPagingAndAnUnavailableCurrentSession() {
        UUID currentId = UUID.randomUUID();
        when(sessions.findOwned(ACCOUNT_ID, currentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current(ACCOUNT_ID, currentId))
                .isInstanceOfSatisfying(SessionFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(SessionFailure.Reason.SESSION_UNAVAILABLE));

        when(sessions.findOwned(ACCOUNT_ID, currentId))
                .thenReturn(Optional.of(session(currentId, NOW.minusSeconds(1))));
        assertThatThrownBy(() -> service.sessions(ACCOUNT_ID, currentId, -1, 20))
                .isInstanceOfSatisfying(SessionFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(SessionFailure.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> service.sessions(ACCOUNT_ID, currentId, 0, 101))
                .isInstanceOf(SessionFailure.class);
    }

    @Test
    void revokesAnOwnedTargetAndInvalidatesItsAuthorizations() {
        UUID currentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AuthenticationSession current = session(currentId, NOW.minusSeconds(60));
        AuthenticationSession target = session(targetId, NOW.minusSeconds(30));
        RevokeAuthenticationSession command = new RevokeAuthenticationSession(
                ACCOUNT_ID, currentId, targetId, "request-revoke"
        );
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(current, target));

        service.revoke(command);

        verify(sessions).recordRevocation(target.revoke(
                SessionRevokeReason.USER_REQUEST,
                NOW
        ));
        verify(authorizations).invalidateAllForSession(
                targetId,
                OAuthAuthorizationInvalidationReason.USER_REQUEST,
                NOW
        );
    }

    @Test
    void hidesMissingTargetsAndRejectsAnUnavailableCurrentSession() {
        UUID currentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        RevokeAuthenticationSession command = new RevokeAuthenticationSession(
                ACCOUNT_ID, currentId, targetId, "request-revoke"
        );
        AuthenticationSession current = session(currentId, NOW.minusSeconds(60));
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID)).thenReturn(List.of(current));

        assertThatThrownBy(() -> service.revoke(command))
                .isInstanceOfSatisfying(SessionFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(SessionFailure.Reason.SESSION_NOT_FOUND));

        when(sessions.findAllLockedByAccountId(ACCOUNT_ID)).thenReturn(List.of(
                current.revoke(SessionRevokeReason.LOGOUT, NOW.minusSeconds(1))
        ));
        assertThatThrownBy(() -> service.revoke(command))
                .isInstanceOfSatisfying(SessionFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(SessionFailure.Reason.SESSION_UNAVAILABLE));

        verifyNoInteractions(authorizations);
    }

    @Test
    void revokesAllOnlyWhenTheCurrentSessionRemainsUsable() {
        UUID currentId = UUID.randomUUID();
        AuthenticationSession current = session(currentId, NOW.minusSeconds(60));
        AuthenticationSession second = session(UUID.randomUUID(), NOW.minusSeconds(30));
        AuthenticationSession alreadyRevoked = session(UUID.randomUUID(), NOW.minusSeconds(90))
                .revoke(SessionRevokeReason.USER_REQUEST, NOW.minusSeconds(1));
        RevokeAllAuthenticationSessions command = new RevokeAllAuthenticationSessions(
                ACCOUNT_ID, currentId, "request-all"
        );
        when(sessions.findAllLockedByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(current, second, alreadyRevoked));

        assertThat(service.revokeAll(command)).isEqualTo(2);
        verify(sessions).recordRevocation(current.revoke(
                SessionRevokeReason.ALL_SESSIONS_REVOKED,
                NOW
        ));
        verify(sessions).recordRevocation(second.revoke(
                SessionRevokeReason.ALL_SESSIONS_REVOKED,
                NOW
        ));
        verify(authorizations).invalidateAllForSession(
                currentId,
                OAuthAuthorizationInvalidationReason.ALL_SESSIONS_REVOKED,
                NOW
        );
        verify(authorizations).invalidateAllForSession(
                second.id(),
                OAuthAuthorizationInvalidationReason.ALL_SESSIONS_REVOKED,
                NOW
        );

        when(sessions.findAllLockedByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        assertThatThrownBy(() -> service.revokeAll(command))
                .isInstanceOf(SessionFailure.class);
    }

    @Test
    void logoutIsIdempotentButCannotTargetAnotherSession() {
        UUID currentId = UUID.randomUUID();
        RevokeAuthenticationSession command = new RevokeAuthenticationSession(
                ACCOUNT_ID, currentId, currentId, "request-logout"
        );
        AuthenticationSession current = session(currentId, NOW.minusSeconds(60));
        when(sessions.findLockedById(currentId)).thenReturn(Optional.of(current));

        service.logout(command);

        verify(sessions).recordRevocation(current.revoke(
                SessionRevokeReason.LOGOUT,
                NOW
        ));
        verify(authorizations).invalidateAllForSession(
                currentId,
                OAuthAuthorizationInvalidationReason.LOGOUT,
                NOW
        );
        assertThatThrownBy(() -> service.logout(new RevokeAuthenticationSession(
                ACCOUNT_ID,
                currentId,
                UUID.randomUUID(),
                "request-invalid"
        ))).isInstanceOfSatisfying(SessionFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(SessionFailure.Reason.INVALID_REQUEST));
    }

    @Test
    void validatesRequestContextBeforeRevoking() {
        assertThatThrownBy(() -> service.revoke(new RevokeAuthenticationSession(
                ACCOUNT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                " "
        ))).isInstanceOf(SessionFailure.class);

        verifyNoInteractions(sessions, authorizations);
    }

    private static AuthenticationSession session(UUID id, Instant createdAt) {
        return AuthenticationSession.create(
                id,
                ACCOUNT_ID,
                "vk-platform-bff",
                createdAt,
                NOW.plusSeconds(3600)
        );
    }
}
