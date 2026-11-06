package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.OAuthRefreshTokenRepository;
import com.vkplatform.iam.session.domain.OAuthRefreshToken;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthRefreshTokenEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class JpaOAuthRefreshTokenRepository implements OAuthRefreshTokenRepository {
    private final SpringDataOAuthRefreshTokenRepository refreshTokens;

    JpaOAuthRefreshTokenRepository(SpringDataOAuthRefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findAuthorizationIdByTokenDigest(byte[] tokenDigest) {
        return refreshTokens.findAuthorizationIdByTokenDigest(tokenDigest);
    }

    @Override
    @Transactional
    public Optional<OAuthRefreshToken> findLockedByTokenDigest(byte[] tokenDigest) {
        return refreshTokens.findLockedByTokenDigest(tokenDigest).map(OAuthRefreshTokenEntity::toDomain);
    }

    @Override
    @Transactional
    public void create(OAuthRefreshToken token, byte[] tokenDigest) {
        refreshTokens.save(new OAuthRefreshTokenEntity(token, tokenDigest));
    }

    @Override
    @Transactional
    public void recordConsumption(OAuthRefreshToken consumed) {
        applyLocked(consumed);
    }

    @Override
    @Transactional
    public void recordReuse(OAuthRefreshToken reused) {
        applyLocked(reused);
    }

    private void applyLocked(OAuthRefreshToken token) {
        refreshTokens.findLockedById(token.id())
                .orElseThrow(() -> new IllegalStateException("refresh token does not exist"))
                .apply(token);
    }
}
