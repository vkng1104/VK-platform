package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.StartSignInEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationChallengeRepository;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationOperationGuardRepository;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeGenerator;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeHasher;
import com.vkplatform.iam.verification.application.port.out.VerificationEmailSender;
import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.EmailDeliveryStatus;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationCode;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
    private static final UUID ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final byte[] EMAIL_FINGERPRINT = bytes(1);
    private static final byte[] REQUESTER_FINGERPRINT = bytes(2);
    private static final byte[] CODE_DIGEST = bytes(3);

    /** Allows two starts per window so a test can fill a window with two stored challenges. */
    private static final VerificationPolicy TIGHT_POLICY = new VerificationPolicy(
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofMinutes(15),
            2,
            Duration.ofHours(1),
            2,
            Duration.ofHours(24),
            2,
            5
    );

    private EmailVerificationChallengeRepository challenges;
    private EmailVerificationOperationGuardRepository operationGuards;
    private RecordingTransactions transactions;
    private RecordingSender sender;
    private RecordingHasher hasher;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        challenges = mock(EmailVerificationChallengeRepository.class);
        operationGuards = mock(EmailVerificationOperationGuardRepository.class);
        transactions = new RecordingTransactions();
        sender = new RecordingSender();
        hasher = new RecordingHasher();
        service = service(VerificationPolicy.defaults());
    }

    @Test
    void startsAPurposeBoundChallengeThroughOutboundPorts() {
        VerificationChallengeStarted result = service.start(new StartEmailVerification(
                " Reviewer@EXAMPLE.COM ",
                "restricted_resource_access",
                "192.0.2.15"
        ));

        assertThat(sender.recipient.value()).isEqualTo("Reviewer@example.com");
        assertThat(sender.code.value()).isEqualTo("123456");
        assertThat(sender.expiresAt).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.maskedEmail()).isEqualTo("R******r@example.com");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.resendAfter()).isEqualTo(NOW.plusSeconds(60));
        assertThat(created()).usingRecursiveComparison().isEqualTo(VerificationChallenge.pending(
                result.id(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                EMAIL_FINGERPRINT,
                REQUESTER_FINGERPRINT,
                CODE_DIGEST,
                5,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                NOW
        ));
        assertThat(hasher.digestId).isEqualTo(result.id());
        assertThat(hasher.digestPurpose).isEqualTo(VerificationPurpose.RESTRICTED_RESOURCE_ACCESS);
        assertThat(hasher.requesterAddress).isEqualTo("192.0.2.15");
        verify(challenges).invalidateActive(
                aryEq(EMAIL_FINGERPRINT),
                eq(VerificationPurpose.RESTRICTED_RESOURCE_ACCESS),
                eq(NOW)
        );
        verify(challenges).markSent(result.id(), NOW);
    }

    @Test
    void locksTheGuardFirstAndChecksEveryLimitBeforeReplacingActiveChallenges() {
        service.start(restrictedStart("192.0.2.15"));

        InOrder order = inOrder(operationGuards, challenges);
        order.verify(operationGuards).lock();
        order.verify(challenges).findLatestByEmailFingerprint(aryEq(EMAIL_FINGERPRINT));
        order.verify(challenges).findNewestByEmailFingerprintCreatedSince(
                aryEq(EMAIL_FINGERPRINT),
                eq(NOW.minus(Duration.ofMinutes(15))),
                eq(3)
        );
        order.verify(challenges).findNewestCreatedSince(NOW.minus(Duration.ofHours(24)), 100);
        order.verify(challenges).findNewestByRequesterFingerprintCreatedSince(
                aryEq(REQUESTER_FINGERPRINT),
                eq(NOW.minus(Duration.ofHours(1))),
                eq(10)
        );
        order.verify(challenges).invalidateActive(
                aryEq(EMAIL_FINGERPRINT),
                eq(VerificationPurpose.RESTRICTED_RESOURCE_ACCESS),
                eq(NOW)
        );
        order.verify(challenges).create(any());
        order.verify(challenges).markSent(any(), eq(NOW));
    }

    @Test
    void registersTheChallengeInOneTransactionAndDeliversOutsideIt() {
        List<String> events = recordTransactionStates();

        service.start(restrictedStart("192.0.2.15"));

        assertThat(events).containsExactly(
                "lock in transaction 1",
                "create in transaction 1",
                "send outside a transaction",
                "mark sent outside a transaction"
        );
        assertThat(transactions.committed()).isEqualTo(1);
    }

    @Test
    void omitsTheRequesterFingerprintAndItsLimitWhenNoAddressIsAvailable() {
        service.start(new StartEmailVerification(
                "reader@example.com",
                "restricted_resource_access",
                " "
        ));

        assertThat(created().requesterFingerprint()).isNull();
        assertThat(hasher.requesterAddress).isNull();
        verify(challenges, never()).findNewestByRequesterFingerprintCreatedSince(any(), any(), anyInt());
    }

    @Test
    void rejectsInvalidAddressesAndPurposesBeforeWritingAnything() {
        assertThatThrownBy(() -> service.start(new StartEmailVerification(
                "not-an-email",
                "restricted_resource_access",
                "ip"
        ))).isInstanceOfSatisfying(VerificationFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_EMAIL));
        assertThatThrownBy(() -> service.start(new StartEmailVerification(
                "reader@example.com",
                "login",
                "ip"
        ))).isInstanceOfSatisfying(VerificationFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_PURPOSE));

        assertThat(transactions.started()).isZero();
        assertThat(sender.recipient).isNull();
        verifyNoInteractions(challenges, operationGuards);
    }

    @Test
    void recordsFailedDeliveryOutsideTheTransactionAndReturnsOnlyTheStableFailureReason() {
        IllegalStateException providerFailure = new IllegalStateException("provider secret");
        sender.failure = providerFailure;
        List<String> events = recordTransactionStates();

        assertThatThrownBy(() -> service.start(restrictedStart("192.0.2.15")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.DELIVERY_UNAVAILABLE);
                    assertThat(failure.getCause()).isSameAs(providerFailure);
                });

        UUID createdId = created().id();
        verify(challenges).markFailed(createdId);
        verify(challenges, never()).markSent(any(), any());
        assertThat(events).containsExactly(
                "lock in transaction 1",
                "create in transaction 1",
                "send outside a transaction",
                "mark failed outside a transaction"
        );
    }

    @Test
    void keepsTheDeliveryFailureWhenRecordingItAlsoFails() {
        IllegalStateException providerFailure = new IllegalStateException("provider secret");
        IllegalStateException recordFailure = new IllegalStateException("state changed");
        sender.failure = providerFailure;
        doThrow(recordFailure).when(challenges).markFailed(any());

        assertThatThrownBy(() -> service.start(restrictedStart("192.0.2.15")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.DELIVERY_UNAVAILABLE);
                    assertThat(failure.getCause()).isSameAs(providerFailure);
                    assertThat(providerFailure.getSuppressed()).containsExactly(recordFailure);
                });
    }

    @Test
    void rejectsAStartDuringTheResendCooldown() {
        when(challenges.findLatestByEmailFingerprint(aryEq(EMAIL_FINGERPRINT)))
                .thenReturn(Optional.of(storedAt(NOW.minusSeconds(10))));

        assertRateLimited(service, NOW.plusSeconds(50));
    }

    @Test
    void allowsAStartOnceTheResendCooldownHasPassed() {
        when(challenges.findLatestByEmailFingerprint(aryEq(EMAIL_FINGERPRINT)))
                .thenReturn(Optional.of(storedAt(NOW.minusSeconds(60))));

        service.start(restrictedStart("192.0.2.15"));

        verify(challenges).create(any());
    }

    @Test
    void rejectsAStartUntilTheOldestStartLeavesAFullEmailWindow() {
        EmailVerificationService tight = service(TIGHT_POLICY);
        when(challenges.findNewestByEmailFingerprintCreatedSince(
                aryEq(EMAIL_FINGERPRINT),
                eq(NOW.minus(Duration.ofMinutes(15))),
                eq(2)
        )).thenReturn(List.of(storedAt(NOW.minus(Duration.ofMinutes(5))), storedAt(NOW.minus(Duration.ofMinutes(10)))));

        assertRateLimited(tight, NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void allowsAStartWhileTheEmailWindowHasRoom() {
        EmailVerificationService tight = service(TIGHT_POLICY);
        when(challenges.findNewestByEmailFingerprintCreatedSince(any(), any(), anyInt()))
                .thenReturn(List.of(storedAt(NOW.minus(Duration.ofMinutes(5)))));

        tight.start(restrictedStart("192.0.2.15"));

        verify(challenges).create(any());
    }

    @Test
    void rejectsAStartUntilTheOldestStartLeavesAFullGlobalWindow() {
        EmailVerificationService tight = service(TIGHT_POLICY);
        when(challenges.findNewestCreatedSince(NOW.minus(Duration.ofHours(24)), 2))
                .thenReturn(List.of(storedAt(NOW.minus(Duration.ofHours(12))), storedAt(NOW.minus(Duration.ofHours(23)))));

        assertRateLimited(tight, NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void rejectsAStartUntilTheOldestStartLeavesAFullRequesterWindow() {
        EmailVerificationService tight = service(TIGHT_POLICY);
        when(challenges.findNewestByRequesterFingerprintCreatedSince(
                aryEq(REQUESTER_FINGERPRINT),
                eq(NOW.minus(Duration.ofHours(1))),
                eq(2)
        )).thenReturn(List.of(storedAt(NOW.minus(Duration.ofMinutes(30))), storedAt(NOW.minus(Duration.ofMinutes(50)))));

        assertRateLimited(tight, NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void reportsTheLatestRetryTimeWhenSeveralLimitsApply() {
        EmailVerificationService tight = service(TIGHT_POLICY);
        when(challenges.findLatestByEmailFingerprint(any()))
                .thenReturn(Optional.of(storedAt(NOW.minusSeconds(10))));
        when(challenges.findNewestByEmailFingerprintCreatedSince(any(), any(), anyInt()))
                .thenReturn(List.of(storedAt(NOW.minusSeconds(10)), storedAt(NOW.minus(Duration.ofMinutes(10)))));
        when(challenges.findNewestCreatedSince(any(), anyInt()))
                .thenReturn(List.of(storedAt(NOW.minusSeconds(10)), storedAt(NOW.minus(Duration.ofHours(23)))));
        when(challenges.findNewestByRequesterFingerprintCreatedSince(any(), any(), anyInt()))
                .thenReturn(List.of(storedAt(NOW.minusSeconds(10)), storedAt(NOW.minus(Duration.ofMinutes(50)))));

        assertRateLimited(tight, NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void keepsSignInBehindTheExplicitModuleApi() {
        assertThatThrownBy(() -> service.start(new StartEmailVerification(
                "reader@example.com",
                "sign_in",
                "192.0.2.15"
        ))).isInstanceOfSatisfying(VerificationFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_PURPOSE));

        service.startSignIn(new StartSignInEmailVerification(
                "reader@example.com",
                "192.0.2.15"
        ));
        assertThat(created().purpose()).isEqualTo(VerificationPurpose.SIGN_IN);
        verify(challenges).invalidateActive(aryEq(EMAIL_FINGERPRINT), eq(VerificationPurpose.SIGN_IN), eq(NOW));

        when(challenges.findLockedById(ID)).thenReturn(Optional.of(sent(VerificationPurpose.SIGN_IN)));
        EmailVerified verified = service.verifySignIn(new VerifyEmailCode(ID.toString(), "123456"));
        assertThat(hasher.digestPurpose).isEqualTo(VerificationPurpose.SIGN_IN);
        assertThat(verified.purpose()).isEqualTo("sign_in");
        assertThat(verified.identityFingerprint()).isEqualTo(EMAIL_FINGERPRINT);
    }

    @Test
    void verifiesASentChallengeWithTheRightCodeInsideOneTransaction() {
        VerificationChallenge challenge = sent(VerificationPurpose.RESTRICTED_RESOURCE_ACCESS);
        List<String> events = new ArrayList<>();
        when(challenges.findLockedById(ID)).thenAnswer(invocation -> {
            events.add("lock " + transactions.state());
            return Optional.of(challenge);
        });
        doAnswer(invocation -> events.add("record verification " + transactions.state()))
                .when(challenges).recordVerification(any());

        EmailVerified completed = service.verify(new VerifyEmailCode(ID.toString(), "123456"));

        assertThat(completed.id()).isEqualTo(ID);
        assertThat(completed.purpose()).isEqualTo("restricted_resource_access");
        assertThat(completed.verifiedAt()).isEqualTo(NOW);
        assertThat(completed.identityFingerprint()).isEqualTo(EMAIL_FINGERPRINT);
        assertThat(hasher.digestId).isEqualTo(ID);
        assertThat(recordedVerification()).usingRecursiveComparison().isEqualTo(challenge.verify(NOW));
        assertThat(events).containsExactly("lock in transaction 1", "record verification in transaction 1");
        verify(challenges, never()).recordFailedAttempt(any());
    }

    @Test
    void acceptsTheLastAttemptJustBeforeExpiry() {
        when(challenges.findLockedById(ID)).thenReturn(Optional.of(stored(
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                CODE_DIGEST,
                4,
                NOW.plusNanos(1),
                EmailDeliveryStatus.SENT,
                null,
                null
        )));

        assertThat(service.verify(new VerifyEmailCode(ID.toString(), "123456")).verifiedAt()).isEqualTo(NOW);
        assertThat(recordedVerification().attemptCount()).isEqualTo(4);
    }

    @Test
    void recordsAWrongCodeAsAFailedAttemptAndCommitsItBeforeRejecting() {
        VerificationChallenge challenge = stored(
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                bytes(9),
                1,
                NOW.plusSeconds(240),
                EmailDeliveryStatus.SENT,
                null,
                null
        );
        when(challenges.findLockedById(ID)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(ID.toString(), "654321")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_OR_EXPIRED_CODE));

        ArgumentCaptor<VerificationChallenge> failed = ArgumentCaptor.forClass(VerificationChallenge.class);
        verify(challenges).recordFailedAttempt(failed.capture());
        assertThat(failed.getValue()).usingRecursiveComparison().isEqualTo(challenge.failAttempt());
        assertThat(failed.getValue().attemptCount()).isEqualTo(2);
        verify(challenges, never()).recordVerification(any());
        assertThat(transactions.committed()).isEqualTo(1);
        assertThat(transactions.rolledBack()).isZero();
    }

    @ParameterizedTest
    @MethodSource("challengesThatRejectEveryCode")
    void rejectsChallengesThatNoLongerAcceptAttemptsWithoutChangingThem(Optional<VerificationChallenge> stored) {
        when(challenges.findLockedById(ID)).thenReturn(stored);

        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(ID.toString(), "123456")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_OR_EXPIRED_CODE));

        verify(challenges, never()).recordFailedAttempt(any());
        verify(challenges, never()).recordVerification(any());
        assertThat(transactions.committed()).isEqualTo(1);
    }

    static Stream<Arguments> challengesThatRejectEveryCode() {
        VerificationPurpose purpose = VerificationPurpose.RESTRICTED_RESOURCE_ACCESS;
        Instant expiresAt = NOW.plusSeconds(240);
        return Stream.of(
                argumentSet("missing", Optional.empty()),
                argumentSet("delivery still pending", Optional.of(
                        stored(purpose, CODE_DIGEST, 0, expiresAt, EmailDeliveryStatus.PENDING, null, null))),
                argumentSet("delivery failed", Optional.of(
                        stored(purpose, CODE_DIGEST, 0, expiresAt, EmailDeliveryStatus.FAILED, null, null))),
                argumentSet("invalidated by a newer challenge", Optional.of(
                        stored(purpose, CODE_DIGEST, 0, expiresAt, EmailDeliveryStatus.SENT, null, NOW))),
                argumentSet("already verified", Optional.of(
                        stored(purpose, CODE_DIGEST, 0, expiresAt, EmailDeliveryStatus.SENT, NOW, null))),
                argumentSet("expired at this instant", Optional.of(
                        stored(purpose, CODE_DIGEST, 0, NOW, EmailDeliveryStatus.SENT, null, null))),
                argumentSet("out of attempts", Optional.of(
                        stored(purpose, CODE_DIGEST, 5, expiresAt, EmailDeliveryStatus.SENT, null, null)))
        );
    }

    @Test
    void rejectsMalformedIdentifiersAndCodesBeforeOpeningATransaction() {
        assertThatThrownBy(() -> service.verify(new VerifyEmailCode("not-a-uuid", "123456")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_ID));
        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(null, "123456")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_ID));
        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(ID.toString(), "12345")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_CODE_FORMAT));

        assertThat(transactions.started()).isZero();
        verifyNoInteractions(challenges, operationGuards);
    }

    private EmailVerificationService service(VerificationPolicy policy) {
        return new EmailVerificationService(
                challenges,
                operationGuards,
                transactions,
                sender,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new VerificationCode("123456"),
                hasher,
                policy
        );
    }

    private void assertRateLimited(EmailVerificationService rateLimited, Instant expectedRetryAt) {
        assertThatThrownBy(() -> rateLimited.start(restrictedStart("192.0.2.15")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.RATE_LIMITED);
                    assertThat(failure.retryAt()).isEqualTo(expectedRetryAt);
                });

        verify(operationGuards).lock();
        verify(challenges, never()).invalidateActive(any(), any(), any());
        verify(challenges, never()).create(any());
        verify(challenges, never()).markSent(any(), any());
        assertThat(sender.recipient).isNull();
        assertThat(transactions.rolledBack()).isEqualTo(1);
    }

    private List<String> recordTransactionStates() {
        List<String> events = new ArrayList<>();
        doAnswer(invocation -> events.add("lock " + transactions.state())).when(operationGuards).lock();
        doAnswer(invocation -> events.add("create " + transactions.state())).when(challenges).create(any());
        doAnswer(invocation -> events.add("mark sent " + transactions.state()))
                .when(challenges).markSent(any(), any());
        doAnswer(invocation -> events.add("mark failed " + transactions.state()))
                .when(challenges).markFailed(any());
        sender.onSend = () -> events.add("send " + transactions.state());
        return events;
    }

    private VerificationChallenge created() {
        ArgumentCaptor<VerificationChallenge> created = ArgumentCaptor.forClass(VerificationChallenge.class);
        verify(challenges).create(created.capture());
        return created.getValue();
    }

    private VerificationChallenge recordedVerification() {
        ArgumentCaptor<VerificationChallenge> verified = ArgumentCaptor.forClass(VerificationChallenge.class);
        verify(challenges).recordVerification(verified.capture());
        return verified.getValue();
    }

    private static StartEmailVerification restrictedStart(String requesterAddress) {
        return new StartEmailVerification("reader@example.com", "restricted_resource_access", requesterAddress);
    }

    /** A stored challenge of another email that only matters for when it was created. */
    private static VerificationChallenge storedAt(Instant createdAt) {
        return VerificationChallenge.pending(
                UUID.randomUUID(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                bytes(7),
                bytes(8),
                bytes(9),
                5,
                createdAt.plusSeconds(300),
                createdAt.plusSeconds(60),
                createdAt
        );
    }

    private static VerificationChallenge sent(VerificationPurpose purpose) {
        return stored(purpose, CODE_DIGEST, 0, NOW.plusSeconds(240), EmailDeliveryStatus.SENT, null, null);
    }

    private static VerificationChallenge stored(
            VerificationPurpose purpose,
            byte[] otpDigest,
            int attemptCount,
            Instant expiresAt,
            EmailDeliveryStatus deliveryStatus,
            Instant verifiedAt,
            Instant invalidatedAt
    ) {
        Instant createdAt = NOW.minusSeconds(60);
        return new VerificationChallenge(
                ID,
                purpose,
                EMAIL_FINGERPRINT,
                REQUESTER_FINGERPRINT,
                otpDigest,
                attemptCount,
                5,
                expiresAt,
                createdAt.plusSeconds(60),
                deliveryStatus,
                deliveryStatus == EmailDeliveryStatus.SENT ? createdAt.plusSeconds(1) : null,
                verifiedAt,
                invalidatedAt,
                createdAt
        );
    }

    private static final class RecordingSender implements VerificationEmailSender {
        private EmailAddress recipient;
        private VerificationCode code;
        private Instant expiresAt;
        private RuntimeException failure;
        private Runnable onSend = () -> {
        };

        @Override
        public void send(EmailAddress recipient, VerificationCode code, Instant expiresAt) {
            onSend.run();
            if (failure != null) {
                throw failure;
            }
            this.recipient = recipient;
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    private static final class RecordingHasher implements VerificationCodeHasher {
        private String requesterAddress;
        private UUID digestId;
        private VerificationPurpose digestPurpose;

        @Override
        public byte[] emailFingerprint(EmailAddress email) {
            return EMAIL_FINGERPRINT.clone();
        }

        @Override
        public byte[] requesterFingerprint(String requesterAddress) {
            this.requesterAddress = requesterAddress;
            return REQUESTER_FINGERPRINT.clone();
        }

        @Override
        public byte[] codeDigest(UUID id, VerificationPurpose purpose, VerificationCode code) {
            digestId = id;
            digestPurpose = purpose;
            return CODE_DIGEST.clone();
        }
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
