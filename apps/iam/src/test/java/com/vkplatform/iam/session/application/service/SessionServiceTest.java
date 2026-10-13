package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.command.StartPasswordlessSignIn;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.result.AccountSummary;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.api.result.AuthenticationChallengeStarted;
import com.vkplatform.iam.identity.api.result.AuthenticationGrantIssued;
import com.vkplatform.iam.session.api.command.ExchangeSignInGrant;
import com.vkplatform.iam.session.api.command.RevokeAllSessions;
import com.vkplatform.iam.session.api.command.RevokeSession;
import com.vkplatform.iam.session.api.result.EstablishedSession;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T07:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("85d300a2-c59e-49d4-80fc-fce164971a9d");
    private static final String GRANT = "A".repeat(43);

    private RecordingIdentities identities;
    private RecordingSessions sessions;
    private SessionService service;

    @BeforeEach
    void setUp() {
        identities = new RecordingIdentities();
        sessions = new RecordingSessions();
        service = new SessionService(
                identities,
                sessions,
                new SessionPolicy(
                        URI.create("https://iam.example.test"),
                        "vk-platform-web",
                        "vk-platform-bff",
                        Duration.ofMinutes(5),
                        Duration.ofDays(30),
                        Duration.ofDays(30)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void consumesTheGrantAndCreatesAnAbsoluteBrowserSession() {
        EstablishedSession established = service.exchange(new ExchangeSignInGrant(
                GRANT,
                "vk-platform-web",
                "request-1"
        ));

        assertThat(identities.consumed.grant()).isEqualTo(GRANT);
        assertThat(sessions.created.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(sessions.created.clientId()).isEqualTo("vk-platform-bff");
        assertThat(sessions.created.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(sessions.createRequestId).isEqualTo("request-1");
        assertThat(established.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(established.session().id()).isEqualTo(sessions.created.id());
    }

    @Test
    void rejectsAnAudienceMismatchBeforeConsumingTheGrant() {
        assertThatThrownBy(() -> service.exchange(new ExchangeSignInGrant(
                GRANT,
                "another-audience",
                "request-2"
        ))).isInstanceOfSatisfying(SessionFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(SessionFailure.Reason.INVALID_REQUEST));

        assertThat(identities.consumed).isNull();
        assertThat(sessions.created).isNull();
    }

    @Test
    void refusesARevokedOrExpiredCurrentSession() {
        UserSession active = activeSession();
        sessions.owned = active.revoke(SessionRevokeReason.USER_REQUEST, NOW);
        assertThatThrownBy(() -> service.session(ACCOUNT_ID, active.id()))
                .isInstanceOfSatisfying(SessionFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(SessionFailure.Reason.SESSION_UNAVAILABLE));

        sessions.owned = UserSession.create(
                active.id(),
                ACCOUNT_ID,
                "vk-platform-bff",
                NOW.minusSeconds(60),
                NOW
        );
        assertThatThrownBy(() -> service.session(ACCOUNT_ID, active.id()))
                .isInstanceOf(SessionFailure.class);
    }

    @Test
    void distinguishesLogoutFromAdministrativeRevocation() {
        UUID sessionId = UUID.randomUUID();
        service.logout(new RevokeSession(ACCOUNT_ID, sessionId, "request-logout"));
        assertThat(sessions.revokeReason).isEqualTo(SessionRevokeReason.LOGOUT);

        service.revoke(new RevokeSession(ACCOUNT_ID, sessionId, "request-revoke"));
        assertThat(sessions.revokeReason).isEqualTo(SessionRevokeReason.USER_REQUEST);

        assertThat(service.revokeAll(new RevokeAllSessions(
                ACCOUNT_ID,
                sessionId,
                "request-all"
        ))).isEqualTo(2);
        assertThat(sessions.revokeAllReason).isEqualTo(SessionRevokeReason.ALL_SESSIONS_REVOKED);
    }

    private static UserSession activeSession() {
        return UserSession.create(
                UUID.randomUUID(),
                ACCOUNT_ID,
                "vk-platform-bff",
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600)
        );
    }

    private static final class RecordingIdentities implements PasswordlessIdentityOperations {
        private ConsumeSignInGrant consumed;

        @Override
        public AuthenticatedAccount consumeSignInGrant(ConsumeSignInGrant command) {
            consumed = command;
            return new AuthenticatedAccount(ACCOUNT_ID, NOW.minusSeconds(1));
        }

        @Override
        public AuthenticatedAccount activeAccount(UUID accountId) {
            return new AuthenticatedAccount(accountId, NOW.minusSeconds(1));
        }

        @Override
        public AuthenticationChallengeStarted startSignIn(StartPasswordlessSignIn command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationGrantIssued verifySignIn(VerifyPasswordlessSignIn command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary account(UUID accountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary suspendAccount(AccountAdministrationCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary reactivateAccount(AccountAdministrationCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary deleteAccount(AccountAdministrationCommand command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingSessions implements SessionRepository {
        private UserSession created;
        private String createRequestId;
        private UserSession owned = activeSession();
        private SessionRevokeReason revokeReason;
        private SessionRevokeReason revokeAllReason;

        @Override
        public UserSession create(UserSession session, String requestId) {
            created = session;
            createRequestId = requestId;
            return session;
        }

        @Override
        public UserSession findOwned(UUID accountId, UUID sessionId) {
            return owned;
        }

        @Override
        public List<UserSession> findActiveByAccount(UUID accountId, Instant now) {
            return new ArrayList<>(List.of(owned));
        }

        @Override
        public boolean revokeOwned(
                UUID accountId,
                UUID sessionId,
                SessionRevokeReason reason,
                Instant now,
                String requestId
        ) {
            revokeReason = reason;
            return true;
        }

        @Override
        public int revokeAll(
                UUID accountId,
                UUID exceptSessionId,
                SessionRevokeReason reason,
                Instant now,
                String requestId
        ) {
            revokeAllReason = reason;
            return 2;
        }
    }
}
