package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.application.port.out.OAuthRefreshTokenRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.OAuthRefreshToken;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Business rules behind the OAuth authorization store: whether a session may still use its
 * tokens, how refresh tokens rotate, and what happens when a used refresh token comes back.
 *
 * <p>Callers lock in the order session, then authorization, then refresh token. Every method here
 * keeps that order.
 */
public class OAuthTokenLifecycleService {
    private final AuthenticationSessionRepository sessions;
    private final OAuthAuthorizationRepository authorizations;
    private final OAuthRefreshTokenRepository refreshTokens;
    private final PasswordlessIdentityOperations identities;
    private final Clock clock;
    private final Duration accessTokenTtl;

    public OAuthTokenLifecycleService(
            AuthenticationSessionRepository sessions,
            OAuthAuthorizationRepository authorizations,
            OAuthRefreshTokenRepository refreshTokens,
            PasswordlessIdentityOperations identities,
            Clock clock,
            Duration accessTokenTtl
    ) {
        this.sessions = Objects.requireNonNull(sessions);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.refreshTokens = Objects.requireNonNull(refreshTokens);
        this.identities = Objects.requireNonNull(identities);
        this.clock = Objects.requireNonNull(clock);
        this.accessTokenTtl = Objects.requireNonNull(accessTokenTtl);
    }

    /**
     * Locks the session when its account is active and it can still authorize the client. Returns
     * {@code false} without locking the session when the account is missing or inactive.
     */
    @Transactional
    public boolean lockUsableSession(UUID sessionId, UUID accountId, String clientId) {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(clientId);
        if (identities.findActiveAccount(accountId).isEmpty()) {
            return false;
        }
        return sessions.findLockedById(sessionId)
                .filter(session -> session.canAuthorize(accountId, clientId, clock.instant()))
                .isPresent();
    }

    /** Read-only check that the session can still authorize the client; takes no lock. */
    @Transactional(readOnly = true)
    public boolean isSessionUsable(UUID sessionId, UUID accountId, String clientId) {
        return sessions.findById(sessionId)
                .filter(session -> session.canAuthorize(accountId, clientId, clock.instant()))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<String> findAuthorizationIdByRefreshToken(byte[] tokenDigest) {
        return refreshTokens.findAuthorizationIdByTokenDigest(tokenDigest);
    }

    /**
     * Checks a presented refresh token after its session and authorization are locked. A token
     * that was already consumed is treated as theft: the token is marked reused, its
     * authorization is invalidated, and the session is compromised, all at one time.
     */
    @Transactional
    public RefreshTokenUse useRefreshToken(byte[] tokenDigest, UUID sessionId) {
        OAuthRefreshToken token = refreshTokens.findLockedByTokenDigest(tokenDigest).orElse(null);
        if (token == null) {
            return RefreshTokenUse.UNKNOWN;
        }
        Instant now = clock.instant();
        if (token.consumedAt() != null) {
            refreshTokens.recordReuse(token.detectReuse(now));
            authorizations.invalidate(
                    token.authorizationId(),
                    OAuthAuthorizationInvalidationReason.REFRESH_TOKEN_REUSED,
                    now
            );
            sessions.findLockedById(sessionId)
                    .ifPresent(session -> sessions.recordCompromise(session.compromise(now)));
            return RefreshTokenUse.REUSED;
        }
        return token.isActive(now) ? RefreshTokenUse.ACTIVE : RefreshTokenUse.INACTIVE;
    }

    /**
     * Records a newly issued refresh token in the ledger. The first token starts a family. A
     * later token consumes its predecessor, joins the same family, and refreshes the session.
     */
    @Transactional
    public void recordRefreshTokenIssued(
            String authorizationId,
            UUID sessionId,
            byte[] previousDigest,
            byte[] currentDigest,
            Instant issuedAt,
            Instant expiresAt
    ) {
        UUID nextId = UUID.randomUUID();
        if (previousDigest == null) {
            refreshTokens.create(
                    OAuthRefreshToken.issue(nextId, authorizationId, nextId, null, issuedAt, expiresAt),
                    currentDigest
            );
            return;
        }

        OAuthRefreshToken previous = refreshTokens.findLockedByTokenDigest(previousDigest)
                .orElseThrow(() -> new IllegalStateException("OAuth refresh lineage is missing"));
        Instant now = clock.instant();
        refreshTokens.recordConsumption(previous.consume(nextId, now));
        refreshTokens.create(
                OAuthRefreshToken.issue(
                        nextId,
                        authorizationId,
                        previous.familyId(),
                        previous.id(),
                        issuedAt,
                        expiresAt
                ),
                currentDigest
        );
        sessions.findLockedById(sessionId)
                .ifPresent(session -> sessions.recordRefresh(session.touch(now)));
    }

    /**
     * Works out when a new access token is valid: from the account's token-not-before time at the
     * earliest, and never past the session's expiry. Fails when the account is inactive or the
     * session can no longer authorize the client.
     */
    @Transactional
    public AccessTokenWindow accessTokenWindow(UUID accountId, UUID sessionId, String clientId) {
        AuthenticatedAccount account = identities.findActiveAccount(accountId)
                .orElseThrow(() -> new IllegalStateException("OAuth account is unavailable"));
        Instant now = clock.instant();
        AuthenticationSession session = sessions.findById(sessionId)
                .filter(candidate -> candidate.canAuthorize(accountId, clientId, now))
                .orElseThrow(() -> new IllegalStateException("OAuth browser session is unavailable"));
        Instant notBefore = account.tokenNotBefore().isAfter(now) ? account.tokenNotBefore() : now;
        Instant expiresAt = now.plus(accessTokenTtl);
        if (session.expiresAt().isBefore(expiresAt)) {
            expiresAt = session.expiresAt();
        }
        return new AccessTokenWindow(notBefore, expiresAt);
    }

    /** Invalidates an authorization the authorization server removed. */
    @Transactional
    public void recordAuthorizationRemoved(String authorizationId) {
        authorizations.invalidate(
                authorizationId,
                OAuthAuthorizationInvalidationReason.AUTHORIZATION_REMOVED,
                clock.instant()
        );
    }

    public record AccessTokenWindow(Instant notBefore, Instant expiresAt) {
    }

    public enum RefreshTokenUse {
        UNKNOWN,
        ACTIVE,
        INACTIVE,
        REUSED
    }
}
