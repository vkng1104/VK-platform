package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.OAuthRefreshToken;

import java.util.Optional;

public interface OAuthRefreshTokenRepository {
    /** Finds the authorization a refresh token belongs to without loading or locking the token. */
    Optional<String> findAuthorizationIdByTokenDigest(byte[] tokenDigest);

    /** Locks the ledger entry until the caller's transaction ends. */
    Optional<OAuthRefreshToken> findLockedByTokenDigest(byte[] tokenDigest);

    void create(OAuthRefreshToken token, byte[] tokenDigest);

    /** Stores a consumption the caller applied. The entry is locked before it is written. */
    void recordConsumption(OAuthRefreshToken consumed);

    /** Stores a reuse detection the caller applied. The entry is locked before it is written. */
    void recordReuse(OAuthRefreshToken reused);
}
