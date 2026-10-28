package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class DatabaseValidatingSecurityContextRepository implements SecurityContextRepository {
    private static final String SESSION_ID_DETAIL = "session_id";

    private final SecurityContextRepository delegate;
    private final SessionRepository sessions;
    private final String expectedClientId;
    private final Clock clock;

    DatabaseValidatingSecurityContextRepository(
            SecurityContextRepository delegate,
            SessionRepository sessions,
            String expectedClientId,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.sessions = Objects.requireNonNull(sessions);
        this.expectedClientId = Objects.requireNonNull(expectedClientId);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        SecurityContext context = delegate.loadContext(requestResponseHolder);
        return validate(context, requestResponseHolder.getRequest());
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        DeferredSecurityContext deferred = delegate.loadDeferredContext(request);
        return new DeferredSecurityContext() {
            private SecurityContext context;
            private boolean generated;

            @Override
            public SecurityContext get() {
                if (context == null) {
                    SecurityContext loaded = deferred.get();
                    context = validate(loaded, request);
                    generated = deferred.isGenerated() || context != loaded;
                }
                return context;
            }

            @Override
            public boolean isGenerated() {
                get();
                return generated;
            }
        };
    }

    @Override
    public void saveContext(
            SecurityContext context,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        delegate.saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        if (!delegate.containsContext(request)) {
            return false;
        }
        return loadDeferredContext(request).get().getAuthentication() != null;
    }

    private SecurityContext validate(SecurityContext context, HttpServletRequest request) {
        Authentication authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return context;
        }
        UUID accountId = uuid(authentication.getName());
        UUID sessionId = sessionId(authentication);
        if (accountId != null && sessionId != null) {
            try {
                UserSession session = sessions.findOwned(accountId, sessionId);
                if (session.canUse(expectedClientId, clock.instant())) {
                    return context;
                }
            } catch (SessionFailure failure) {
                if (failure.reason() != SessionFailure.Reason.SESSION_NOT_FOUND) {
                    throw failure;
                }
            }
        }
        invalidate(request);
        return SecurityContextHolder.createEmptyContext();
    }

    private static UUID sessionId(Authentication authentication) {
        if (!(authentication.getDetails() instanceof Map<?, ?> details)) {
            return null;
        }
        Object value = details.get(SESSION_ID_DETAIL);
        return value == null ? null : uuid(value.toString());
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void invalidate(HttpServletRequest request) {
        HttpSession browserSession = request.getSession(false);
        if (browserSession == null) {
            return;
        }
        try {
            browserSession.invalidate();
        } catch (IllegalStateException ignored) {
            // A concurrent request may already have invalidated the same browser session.
        }
    }
}
