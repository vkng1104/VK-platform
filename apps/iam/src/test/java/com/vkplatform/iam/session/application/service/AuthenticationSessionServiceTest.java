package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private AuthenticationSessionService service;

    @BeforeEach
    void setUp() {
        identities = mock(PasswordlessIdentityOperations.class);
        sessions = mock(AuthenticationSessionRepository.class);
        service = new AuthenticationSessionService(
                identities,
                sessions,
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
}
