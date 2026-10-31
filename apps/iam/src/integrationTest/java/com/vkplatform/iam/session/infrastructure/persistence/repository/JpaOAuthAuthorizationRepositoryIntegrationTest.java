package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaOAuthAuthorizationRepositoryIntegrationTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("c5421488-69d8-41c7-af62-17826861e1c3");
    private static final UUID SESSION_ID = UUID.fromString("35b8a1b6-7df8-4ce1-ab5b-17093e6114f1");
    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        insertBffClient();
        jdbcTemplate.update("""
                INSERT INTO iam_identity.accounts (
                    id, status, token_not_before, version, created_at, updated_at
                ) VALUES (?, 'active', ?, 0, ?, ?)
                """,
                ACCOUNT_ID,
                timestamp(NOW),
                timestamp(NOW),
                timestamp(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, last_refreshed_at, expires_at
                ) VALUES (?, ?, 'vk-platform-bff', 'active', ?, ?, ?)
                """,
                SESSION_ID,
                ACCOUNT_ID,
                timestamp(NOW),
                timestamp(NOW),
                timestamp(NOW.plusSeconds(3600))
        );
    }

    @Test
    void flywaySchemaMatchesTheRefreshAwareAuthorizationEntity() {
        assertThat(OAuthAuthorizationEntity_.authorizationCodeDigest).isNotNull();
        assertThat(OAuthAuthorizationEntity_.sessionId).isNotNull();
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'iam_session'
                  AND table_name = 'oauth_authorizations'
                """, String.class)).containsExactlyInAnyOrder(
                "id",
                "registered_client_id",
                "principal_name",
                "session_id",
                "authorization_grant_type",
                "authorized_scopes",
                "attributes_json",
                "state_digest",
                "authorization_code_digest",
                "authorization_code_issued_at",
                "authorization_code_expires_at",
                "authorization_code_metadata_json",
                "access_token_digest",
                "access_token_issued_at",
                "access_token_expires_at",
                "access_token_metadata_json",
                "access_token_type",
                "access_token_scopes",
                "refresh_token_digest",
                "refresh_token_issued_at",
                "refresh_token_expires_at",
                "refresh_token_metadata_json",
                "invalidated_at",
                "invalidation_reason",
                "version"
        );
    }

    @Test
    void databaseRejectsUnsupportedGrantsAndMalformedCodeDigests() {
        assertThatThrownBy(() -> insertAuthorization("refresh_token", new byte[32]))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAuthorization("authorization_code", new byte[31]))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertAuthorization(String grantType, byte[] codeDigest) {
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_authorizations (
                    id,
                    registered_client_id,
                    principal_name,
                    session_id,
                    authorization_grant_type,
                    authorized_scopes,
                    attributes_json,
                    authorization_code_digest,
                    authorization_code_issued_at,
                    authorization_code_expires_at,
                    authorization_code_metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, '{}', ?, ?, ?, '{}')
                """,
                UUID.randomUUID().toString(),
                "bff:vk-platform-bff",
                ACCOUNT_ID.toString(),
                SESSION_ID,
                grantType,
                "api.access",
                codeDigest,
                timestamp(NOW),
                timestamp(NOW.plusSeconds(300))
        );
    }

    private void insertBffClient() {
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_clients (
                    id, client_id, client_id_issued_at, client_secret, client_name,
                    client_authentication_methods, authorization_grant_types,
                    redirect_uris, post_logout_redirect_uris, scopes,
                    client_settings, token_settings, version
                ) VALUES (
                    'bff:vk-platform-bff', 'vk-platform-bff', ?, '{noop}test-secret',
                    'Test BFF', 'client_secret_basic', 'authorization_code,refresh_token',
                    'https://web.example.test/callback', '', 'api.access', '{}', '{}', 0
                ) ON CONFLICT (id) DO NOTHING
                """, timestamp(NOW));
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
