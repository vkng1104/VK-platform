package com.vkplatform.iam.verification.application;

import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
    private static final byte[] OTP_PEPPER = "otp-pepper-must-be-at-least-32-bytes".getBytes();
    private static final byte[] FINGERPRINT_KEY = "fingerprint-key-must-be-32-bytes!!".getBytes();

    private RecordingRepository repository;
    private RecordingSender sender;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        repository = new RecordingRepository();
        sender = new RecordingSender();
        service = new EmailVerificationService(
                repository,
                sender,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new FixedSecureRandom(),
                OTP_PEPPER,
                FINGERPRINT_KEY,
                VerificationPolicy.defaults()
        );
    }

    @Test
    void startsAPurposeBoundChallengeWithoutPersistingTheAddressOrCode() {
        EmailVerificationService.StartedVerification result = service.start(
                " Reviewer@EXAMPLE.COM ",
                "restricted_resource_access",
                "192.0.2.15"
        );

        assertThat(sender.recipient).isEqualTo("Reviewer@example.com");
        assertThat(sender.code).isEqualTo("123456");
        assertThat(result.maskedEmail()).isEqualTo("R******r@example.com");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.resendAfter()).isEqualTo(NOW.plusSeconds(60));
        assertThat(repository.created.emailFingerprint()).hasSize(32);
        assertThat(repository.created.requesterFingerprint()).hasSize(32);
        assertThat(repository.created.otpDigest()).hasSize(32);
        assertThat(repository.created.otpDigest()).isNotEqualTo(sender.code.getBytes());
        assertThat(repository.sentId).isEqualTo(repository.created.id());
    }

    @Test
    void rejectsInvalidAddressesAndPurposesBeforeWritingAnything() {
        assertThatThrownBy(() -> service.start("not-an-email", "restricted_resource_access", "ip"))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_EMAIL));
        assertThatThrownBy(() -> service.start("reader@example.com", "login", "ip"))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_PURPOSE));
        assertThat(repository.created).isNull();
    }

    @Test
    void recordsFailedDeliveryAndReturnsOnlyTheStableFailureReason() {
        sender.failure = new IllegalStateException("provider secret");

        assertThatThrownBy(() -> service.start(
                "reader@example.com",
                "restricted_resource_access",
                "192.0.2.15"
        )).isInstanceOfSatisfying(VerificationFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.DELIVERY_UNAVAILABLE));

        assertThat(repository.failedId).isEqualTo(repository.created.id());
        assertThat(repository.sentId).isNull();
    }

    @Test
    void verifiesAWellFormedChallengeAndKeepsAllOtherFailuresGeneric() {
        String id = "123e4567-e89b-42d3-a456-426614174000";
        repository.verificationAttempt = EmailVerificationRepository.VerificationAttempt.success();

        EmailVerificationService.CompletedVerification completed = service.verify(id, "123456");

        assertThat(completed.id()).isEqualTo(UUID.fromString(id));
        assertThat(completed.purpose().wireValue()).isEqualTo("restricted_resource_access");
        assertThat(completed.verifiedAt()).isEqualTo(NOW);
        assertThat(repository.candidateDigest).hasSize(32);

        repository.verificationAttempt = EmailVerificationRepository.VerificationAttempt.rejected();
        assertThatThrownBy(() -> service.verify(id, "654321"))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason())
                                .isEqualTo(VerificationFailure.Reason.INVALID_OR_EXPIRED_CODE));
    }

    @Test
    void rejectsMalformedIdentifiersAndCodesBeforeOpeningARepositoryTransaction() {
        assertThatThrownBy(() -> service.verify("not-a-uuid", "123456"))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_ID));
        assertThatThrownBy(() -> service.verify("123e4567-e89b-42d3-a456-426614174000", "12345"))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_CODE_FORMAT));
        assertThat(repository.verifiedId).isNull();
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override
        public int nextInt(int bound) {
            return 123456;
        }
    }

    private static final class RecordingSender implements VerificationEmailSender {
        private String recipient;
        private String code;
        private RuntimeException failure;

        @Override
        public void send(String recipient, String code, Instant expiresAt) {
            if (failure != null) {
                throw failure;
            }
            this.recipient = recipient;
            this.code = code;
        }
    }

    private static final class RecordingRepository implements EmailVerificationRepository {
        private VerificationChallenge created;
        private UUID sentId;
        private UUID failedId;
        private UUID verifiedId;
        private byte[] candidateDigest;
        private VerificationAttempt verificationAttempt = VerificationAttempt.rejected();

        @Override
        public void createPending(VerificationChallenge challenge, VerificationPolicy policy) {
            created = challenge;
        }

        @Override
        public void markSent(UUID id, Instant sentAt) {
            sentId = id;
        }

        @Override
        public void markFailed(UUID id) {
            failedId = id;
        }

        @Override
        public VerificationAttempt verify(UUID id, byte[] candidateDigest, Instant now) {
            verifiedId = id;
            this.candidateDigest = candidateDigest.clone();
            return verificationAttempt;
        }
    }
}
