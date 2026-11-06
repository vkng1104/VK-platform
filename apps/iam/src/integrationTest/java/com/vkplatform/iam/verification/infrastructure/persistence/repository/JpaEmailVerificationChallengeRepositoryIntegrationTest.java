package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.domain.EmailDeliveryStatus;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity_;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "iam.email.cleanup-enabled=false"
})
class JpaEmailVerificationChallengeRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    @Autowired
    private JpaEmailVerificationChallengeRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_identity.email_verification_challenges");
        statistics().clear();
    }

    @Test
    void flywaySchemaMatchesTheGeneratedMetamodel() {
        assertThat(EmailVerificationChallengeEntity_.id).isNotNull();
        assertThat(EmailVerificationChallengeEntity_.createdAt).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('iam_identity.email_verification_challenges')::text",
                String.class
        )).isEqualTo("iam_identity.email_verification_challenges");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.email_verification_challenges')::text",
                String.class
        )).isNull();

        VerificationChallenge invalid = pending(bytes(55), bytes(56), new byte[31], NOW);
        assertThatThrownBy(() -> repository.create(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsAChallengeAndFindsItLocked() {
        VerificationChallenge restricted = pending(bytes(1), bytes(2), bytes(3), NOW);
        VerificationChallenge signIn = VerificationChallenge.pending(
                UUID.randomUUID(),
                VerificationPurpose.SIGN_IN,
                bytes(4),
                null,
                bytes(5),
                5,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                NOW
        );

        repository.create(restricted);
        repository.create(signIn);

        assertThat(repository.findLockedById(restricted.id())).get()
                .usingRecursiveComparison()
                .isEqualTo(restricted);
        assertThat(repository.findLockedById(signIn.id())).get()
                .usingRecursiveComparison()
                .isEqualTo(signIn);
        assertThat(text("purpose", restricted.id())).isEqualTo("restricted_resource_access");
        assertThat(text("purpose", signIn.id())).isEqualTo("sign_in");
        assertThat(text("delivery_status", restricted.id())).isEqualTo("pending");
        assertThat(integer("attempt_count", restricted.id())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requester_fingerprint FROM iam_identity.email_verification_challenges WHERE id = ?",
                byte[].class,
                signIn.id()
        )).isNull();
        assertThat(repository.findLockedById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void locksTheChallengeUntilTheTransactionEnds() throws Exception {
        VerificationChallenge challenge = pending(bytes(6), bytes(7), bytes(8), NOW);
        repository.create(challenge);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.findLockedById(challenge.id())).isPresent();
            assertThatThrownBy(() -> lockWithoutWaiting(challenge.id()))
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getSQLState()).isEqualTo("55P03"));
        });

        lockWithoutWaiting(challenge.id());
    }

    @Test
    void findsTheNewestChallengeOfAnEmailWhateverItsPurposeOrState() {
        byte[] email = bytes(10);
        repository.create(pending(email, bytes(11), bytes(12), NOW.minus(Duration.ofMinutes(10))));
        VerificationChallenge newest = stored(
                email,
                VerificationPurpose.SIGN_IN,
                NOW.minus(Duration.ofMinutes(5)),
                EmailDeliveryStatus.FAILED,
                null,
                NOW.minus(Duration.ofMinutes(4))
        );
        repository.create(newest);
        repository.create(pending(bytes(13), bytes(11), bytes(12), NOW.minus(Duration.ofMinutes(1))));

        assertThat(repository.findLatestByEmailFingerprint(email)).get()
                .usingRecursiveComparison()
                .isEqualTo(newest);
        assertThat(repository.findLatestByEmailFingerprint(bytes(14))).isEmpty();
    }

    @Test
    void findsAnEmailsNewestChallengesFromTheWindowStartUpToTheLimit() {
        byte[] email = bytes(20);
        Instant windowStart = NOW.minus(Duration.ofMinutes(15));
        repository.create(pending(email, null, bytes(21), windowStart.minusSeconds(1)));
        VerificationChallenge atWindowStart = pending(email, null, bytes(22), windowStart);
        VerificationChallenge older = pending(email, bytes(23), bytes(24), NOW.minus(Duration.ofMinutes(10)));
        VerificationChallenge newer = pending(email, null, bytes(25), NOW.minus(Duration.ofMinutes(5)));
        repository.create(atWindowStart);
        repository.create(newer);
        repository.create(older);
        repository.create(pending(bytes(26), null, bytes(27), NOW.minus(Duration.ofMinutes(1))));

        assertThat(ids(repository.findNewestByEmailFingerprintCreatedSince(email, windowStart, 10)))
                .containsExactly(newer.id(), older.id(), atWindowStart.id());
        assertThat(ids(repository.findNewestByEmailFingerprintCreatedSince(email, windowStart, 2)))
                .containsExactly(newer.id(), older.id());
        assertThat(repository.findNewestByEmailFingerprintCreatedSince(bytes(28), windowStart, 10)).isEmpty();
    }

    @Test
    void findsARequestersNewestChallengesFromTheWindowStartUpToTheLimit() {
        byte[] requester = bytes(30);
        Instant windowStart = NOW.minus(Duration.ofHours(1));
        repository.create(pending(bytes(31), requester, bytes(32), windowStart.minusSeconds(1)));
        VerificationChallenge atWindowStart = pending(bytes(33), requester, bytes(34), windowStart);
        VerificationChallenge older = pending(bytes(35), requester, bytes(36), NOW.minus(Duration.ofMinutes(50)));
        VerificationChallenge newer = pending(bytes(37), requester, bytes(38), NOW.minus(Duration.ofMinutes(30)));
        repository.create(atWindowStart);
        repository.create(newer);
        repository.create(older);
        repository.create(pending(bytes(39), null, bytes(40), NOW.minus(Duration.ofMinutes(1))));
        repository.create(pending(bytes(41), bytes(42), bytes(43), NOW.minus(Duration.ofMinutes(1))));

        assertThat(ids(repository.findNewestByRequesterFingerprintCreatedSince(requester, windowStart, 10)))
                .containsExactly(newer.id(), older.id(), atWindowStart.id());
        assertThat(ids(repository.findNewestByRequesterFingerprintCreatedSince(requester, windowStart, 2)))
                .containsExactly(newer.id(), older.id());
    }

    @Test
    void findsTheNewestChallengesOfEveryEmailFromTheWindowStartUpToTheLimit() {
        Instant windowStart = NOW.minus(Duration.ofHours(24));
        repository.create(pending(bytes(50), bytes(51), bytes(52), windowStart.minusSeconds(1)));
        VerificationChallenge atWindowStart = pending(bytes(53), bytes(54), bytes(55), windowStart);
        VerificationChallenge older = pending(bytes(56), null, bytes(57), NOW.minus(Duration.ofHours(23)));
        VerificationChallenge newer = pending(bytes(58), bytes(59), bytes(60), NOW.minus(Duration.ofHours(12)));
        repository.create(atWindowStart);
        repository.create(newer);
        repository.create(older);

        assertThat(ids(repository.findNewestCreatedSince(windowStart, 10)))
                .containsExactly(newer.id(), older.id(), atWindowStart.id());
        assertThat(ids(repository.findNewestCreatedSince(windowStart, 2)))
                .containsExactly(newer.id(), older.id());
    }

    @Test
    void invalidatesOnlyTheEmailsActiveChallengesOfThePurpose() {
        byte[] email = bytes(61);
        Instant earlier = NOW.minus(Duration.ofMinutes(4));
        VerificationChallenge active = pending(email, null, bytes(62), NOW.minus(Duration.ofMinutes(5)));
        VerificationChallenge sentActive = stored(
                email, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, earlier, EmailDeliveryStatus.SENT, null, null
        );
        VerificationChallenge verified = stored(
                email, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, earlier, EmailDeliveryStatus.SENT, earlier, null
        );
        VerificationChallenge alreadyInvalidated = stored(
                email, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, earlier, EmailDeliveryStatus.SENT, null, earlier
        );
        VerificationChallenge otherPurpose = stored(
                email, VerificationPurpose.SIGN_IN, earlier, EmailDeliveryStatus.SENT, null, null
        );
        VerificationChallenge otherEmail = pending(bytes(63), null, bytes(64), earlier);
        for (VerificationChallenge challenge : List.of(
                active, sentActive, verified, alreadyInvalidated, otherPurpose, otherEmail
        )) {
            repository.create(challenge);
        }

        int invalidated = repository.invalidateActive(email, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, NOW);

        assertThat(invalidated).isEqualTo(2);
        assertThat(timestamp("invalidated_at", active.id())).isEqualTo(NOW);
        assertThat(timestamp("invalidated_at", sentActive.id())).isEqualTo(NOW);
        assertThat(timestamp("invalidated_at", verified.id())).isNull();
        assertThat(timestamp("invalidated_at", alreadyInvalidated.id())).isEqualTo(earlier);
        assertThat(timestamp("invalidated_at", otherPurpose.id())).isNull();
        assertThat(timestamp("invalidated_at", otherEmail.id())).isNull();
        assertThat(repository.invalidateActive(email, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, NOW))
                .isZero();
    }

    @Test
    void appliesDeliveryTransitionsOnlyFromPendingState() {
        VerificationChallenge sent = pending(bytes(70), bytes(71), bytes(72), NOW);
        VerificationChallenge failed = pending(bytes(73), bytes(74), bytes(75), NOW.plusSeconds(2));
        VerificationChallenge invalidated = pending(bytes(76), null, bytes(77), NOW);
        repository.create(sent);
        repository.create(failed);
        repository.create(invalidated);
        repository.invalidateActive(bytes(76), VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, NOW.plusSeconds(1));

        repository.markSent(sent.id(), NOW.plusSeconds(3));
        repository.markFailed(failed.id());

        assertThat(text("delivery_status", sent.id())).isEqualTo("sent");
        assertThat(timestamp("sent_at", sent.id())).isEqualTo(NOW.plusSeconds(3));
        assertThat(text("delivery_status", failed.id())).isEqualTo("failed");
        assertThat(timestamp("sent_at", failed.id())).isNull();
        assertThatThrownBy(() -> repository.markSent(sent.id(), NOW.plusSeconds(4)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markFailed(failed.id()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markSent(invalidated.id(), NOW.plusSeconds(4)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(text("delivery_status", invalidated.id())).isEqualTo("pending");
        repository.markFailed(invalidated.id());
        assertThat(text("delivery_status", invalidated.id())).isEqualTo("failed");
        assertThatThrownBy(() -> repository.markFailed(UUID.randomUUID()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void storesFailedAttemptsAndAVerificationWithoutReadingTheLockedRowAgain() {
        VerificationChallenge challenge = pending(bytes(80), bytes(81), bytes(82), NOW);
        repository.create(challenge);
        repository.markSent(challenge.id(), NOW.plusSeconds(1));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        statistics().clear();
        transactions.executeWithoutResult(status -> repository.recordFailedAttempt(
                repository.findLockedById(challenge.id()).orElseThrow().failAttempt()
        ));
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
        transactions.executeWithoutResult(status -> repository.recordVerification(
                repository.findLockedById(challenge.id()).orElseThrow().verify(NOW.plusSeconds(5))
        ));

        assertThat(integer("attempt_count", challenge.id())).isEqualTo(1);
        assertThat(timestamp("verified_at", challenge.id())).isEqualTo(NOW.plusSeconds(5));
        assertThat(text("delivery_status", challenge.id())).isEqualTo("sent");
        assertThatThrownBy(() -> repository.recordFailedAttempt(pending(bytes(83), null, bytes(84), NOW)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("email verification challenge does not exist");
    }

    @Test
    void deletesOneBoundedBatchOfTerminalChallengesOldestFirst() {
        Instant cutoff = NOW.minus(Duration.ofHours(24));
        Instant old = cutoff.minus(Duration.ofHours(24));
        Instant longLived = NOW.plus(Duration.ofDays(30));
        VerificationChallenge expired = pending(bytes(90), null, bytes(91), old);
        VerificationChallenge verified = withExpiry(stored(
                bytes(92), VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, old.plusSeconds(1),
                EmailDeliveryStatus.SENT, old.plusSeconds(2), null
        ), longLived);
        VerificationChallenge invalidated = withExpiry(stored(
                bytes(93), VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, old.plusSeconds(2),
                EmailDeliveryStatus.PENDING, null, old.plusSeconds(3)
        ), longLived);
        VerificationChallenge failed = withExpiry(stored(
                bytes(94), VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, old.plusSeconds(3),
                EmailDeliveryStatus.FAILED, null, null
        ), longLived);
        VerificationChallenge oldButOpen = withExpiry(stored(
                bytes(95), VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, old,
                EmailDeliveryStatus.SENT, null, null
        ), longLived);
        VerificationChallenge verifiedAfterCutoff = withExpiry(stored(
                bytes(96), VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, old,
                EmailDeliveryStatus.SENT, cutoff, null
        ), longLived);
        VerificationChallenge failedAfterCutoff = stored(
                bytes(97), VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, cutoff,
                EmailDeliveryStatus.FAILED, null, null
        );
        VerificationChallenge active = pending(bytes(98), null, bytes(99), NOW);
        for (VerificationChallenge challenge : List.of(
                failed, active, verified, failedAfterCutoff, expired, verifiedAfterCutoff, invalidated, oldButOpen
        )) {
            repository.create(challenge);
        }

        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isEqualTo(2);
        assertThat(challengeIds()).doesNotContain(expired.id(), verified.id());
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isEqualTo(2);
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isZero();
        assertThat(challengeIds()).containsExactlyInAnyOrder(
                oldButOpen.id(),
                verifiedAfterCutoff.id(),
                failedAfterCutoff.id(),
                active.id()
        );
        assertThatThrownBy(() -> repository.deleteTerminalBefore(cutoff, 0))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private void lockWithoutWaiting(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM iam_identity.email_verification_challenges WHERE id = ? FOR UPDATE NOWAIT"
            )) {
                statement.setObject(1, id);
                statement.executeQuery().close();
            } finally {
                connection.rollback();
            }
        }
    }

    private List<UUID> challengeIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM iam_identity.email_verification_challenges",
                UUID.class
        );
    }

    private Integer integer(String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM iam_identity.email_verification_challenges WHERE id = ?",
                Integer.class,
                id
        );
    }

    private String text(String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM iam_identity.email_verification_challenges WHERE id = ?",
                String.class,
                id
        );
    }

    private Instant timestamp(String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM iam_identity.email_verification_challenges WHERE id = ?",
                Instant.class,
                id
        );
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static List<UUID> ids(List<VerificationChallenge> challenges) {
        return challenges.stream().map(VerificationChallenge::id).toList();
    }

    private static VerificationChallenge pending(
            byte[] emailFingerprint,
            byte[] requesterFingerprint,
            byte[] digest,
            Instant createdAt
    ) {
        return VerificationChallenge.pending(
                UUID.randomUUID(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                emailFingerprint,
                requesterFingerprint,
                digest,
                5,
                createdAt.plusSeconds(300),
                createdAt.plusSeconds(60),
                createdAt
        );
    }

    private static VerificationChallenge stored(
            byte[] emailFingerprint,
            VerificationPurpose purpose,
            Instant createdAt,
            EmailDeliveryStatus deliveryStatus,
            Instant verifiedAt,
            Instant invalidatedAt
    ) {
        return new VerificationChallenge(
                UUID.randomUUID(),
                purpose,
                emailFingerprint,
                null,
                bytes(100),
                0,
                5,
                createdAt.plusSeconds(300),
                createdAt.plusSeconds(60),
                deliveryStatus,
                deliveryStatus == EmailDeliveryStatus.SENT ? createdAt : null,
                verifiedAt,
                invalidatedAt,
                createdAt
        );
    }

    private static VerificationChallenge withExpiry(VerificationChallenge challenge, Instant expiresAt) {
        return new VerificationChallenge(
                challenge.id(),
                challenge.purpose(),
                challenge.emailFingerprint(),
                challenge.requesterFingerprint(),
                challenge.otpDigest(),
                challenge.attemptCount(),
                challenge.maxAttempts(),
                expiresAt,
                challenge.resendNotBefore(),
                challenge.deliveryStatus(),
                challenge.sentAt(),
                challenge.verifiedAt(),
                challenge.invalidatedAt(),
                challenge.createdAt()
        );
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
