package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

class DatabaseValidatingSecurityContextRepositoryTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("d0a6bf74-7681-407a-87e6-9890fdc4874e");
    private static final UUID SESSION_ID = UUID.fromString("a99d2585-3c30-4814-9b4a-3af651bfc17f");
    private static final String CLIENT_ID = "vk-platform-bff";
    private static final Instant NOW = Instant.parse("2026-10-27T04:00:00Z");

    private AuthenticationSessionRepository sessions;
    private DatabaseValidatingSecurityContextRepository repository;

    @BeforeEach
    void setUp() {
        sessions = mock(AuthenticationSessionRepository.class);
        repository = new DatabaseValidatingSecurityContextRepository(
                new HttpSessionSecurityContextRepository(),
                sessions,
                CLIENT_ID,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void lazilyRestoresAFrameworkContextWhenTheDatabaseSessionCanAuthorize() {
        BrowserContext browser = browserContext(SESSION_ID.toString());
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(AuthenticationSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                CLIENT_ID,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600)
        )));

        DeferredSecurityContext deferred = repository.loadDeferredContext(browser.request());

        verifyNoInteractions(sessions);
        assertThat(deferred.get().getAuthentication()).isSameAs(browser.context().getAuthentication());
        assertThat(deferred.isGenerated()).isFalse();
        assertThat(browser.session().isInvalid()).isFalse();
        verify(sessions).findById(SESSION_ID);
    }

    @Test
    void clearsTheFrameworkContextAndInvalidatesTheBrowserSessionWhenTheDatabaseSessionIsMissing() {
        BrowserContext browser = browserContext(SESSION_ID.toString());
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());

        SecurityContext context = repository.loadDeferredContext(browser.request()).get();

        assertThat(context.getAuthentication()).isNull();
        assertThat(browser.session().isInvalid()).isTrue();
    }

    @Test
    void clearsTheFrameworkContextWhenTheDatabaseSessionHasReachedItsAbsoluteExpiry() {
        BrowserContext browser = browserContext(SESSION_ID.toString());
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(AuthenticationSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                CLIENT_ID,
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600)
        )));

        SecurityContext context = repository.loadDeferredContext(browser.request()).get();

        assertThat(context.getAuthentication()).isNull();
        assertThat(browser.session().isInvalid()).isTrue();
    }

    @Test
    void rejectsMalformedSessionDetailsWithoutQueryingPersistence() {
        BrowserContext browser = browserContext("not-a-uuid");

        SecurityContext context = repository.loadDeferredContext(browser.request()).get();

        assertThat(context.getAuthentication()).isNull();
        assertThat(browser.session().isInvalid()).isTrue();
        verifyNoInteractions(sessions);
    }

    private static BrowserContext browserContext(String sessionId) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                ACCOUNT_ID.toString(),
                null,
                List.of()
        );
        authentication.setDetails(Map.of(
                "session_id", sessionId,
                "amr", "email_otp"
        ));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return new BrowserContext(request, session, context);
    }

    private record BrowserContext(
            MockHttpServletRequest request,
            MockHttpSession session,
            SecurityContext context
    ) {
    }
}
