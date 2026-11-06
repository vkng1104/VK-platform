package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.domain.LoginIdentity;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.LoginIdentityEntity_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaLoginIdentityRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");

    @Autowired
    private JpaLoginIdentityRepository repository;

    @Autowired
    private JpaAccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        Account account = Account.create(UUID.randomUUID(), NOW);
        accounts.create(account);
        accountId = account.id();
    }

    @Test
    void storesOnlyTheIdentifierFingerprint() {
        assertThat(LoginIdentityEntity_.identifierFingerprint).isNotNull();
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'iam_identity'
                  AND table_name = 'login_identities'
                """, String.class)).doesNotContain("email", "raw_email", "encrypted_contact");
    }

    @Test
    void createsAnIdentityAndFindsItByKindAndFingerprint() {
        LoginIdentity identity = LoginIdentity.linkVerifiedEmail(UUID.randomUUID(), accountId, NOW);

        repository.create(identity, bytes(3));

        assertThat(repository.findByKindAndFingerprint(IdentityKind.EMAIL, bytes(3))).contains(identity);
        assertThat(repository.findByKindAndFingerprint(IdentityKind.EMAIL, bytes(4))).isEmpty();
    }

    @Test
    void databaseRejectsADuplicateOrMalformedFingerprint() {
        repository.create(LoginIdentity.linkVerifiedEmail(UUID.randomUUID(), accountId, NOW), bytes(5));

        assertThatThrownBy(() -> repository.create(
                LoginIdentity.linkVerifiedEmail(UUID.randomUUID(), accountId, NOW),
                bytes(5)
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.create(
                LoginIdentity.linkVerifiedEmail(UUID.randomUUID(), accountId, NOW),
                new byte[31]
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
