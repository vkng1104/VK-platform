package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeGenerator;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeHasher;
import com.vkplatform.iam.verification.application.port.out.VerificationEmailSender;
import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationCode;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");

    private RecordingRepository repository;
    private RecordingSender sender;
    private RecordingHasher hasher;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        repository = new RecordingRepository();
        sender = new RecordingSender();
        hasher = new RecordingHasher();
        VerificationCodeGenerator generator = () -> new VerificationCode("123456");
        service = new EmailVerificationService(
                repository,
                sender,
                Clock.fixed(NOW, ZoneOffset.UTC),
                generator,
                hasher,
                VerificationPolicy.defaults()
        );
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
        assertThat(result.maskedEmail()).isEqualTo("R******r@example.com");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.resendAfter()).isEqualTo(NOW.plusSeconds(60));
        assertThat(repository.created.emailFingerprint()).containsOnly((byte) 1);
        assertThat(repository.created.requesterFingerprint()).containsOnly((byte) 2);
        assertThat(repository.created.otpDigest()).containsOnly((byte) 3);
        assertThat(repository.sentId).isEqualTo(repository.created.id());
        assertThat(hasher.requesterAddress).isEqualTo("192.0.2.15");
    }

    @Test
    void omitsTheRequesterFingerprintWhenNoAddressIsAvailable() {
        service.start(new StartEmailVerification(
                "reader@example.com",
                "restricted_resource_access",
                " "
        ));

        assertThat(repository.created.requesterFingerprint()).isNull();
        assertThat(hasher.requesterAddress).isNull();
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
        assertThat(repository.created).isNull();
    }

    @Test
    void recordsFailedDeliveryAndReturnsOnlyTheStableFailureReason() {
        sender.failure = new IllegalStateException("provider secret");

        assertThatThrownBy(() -> service.start(new StartEmailVerification(
                "reader@example.com",
                "restricted_resource_access",
                "192.0.2.15"
        ))).isInstanceOfSatisfying(VerificationFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.DELIVERY_UNAVAILABLE));

        assertThat(repository.failedId).isEqualTo(repository.created.id());
        assertThat(repository.sentId).isNull();
    }

    @Test
    void verifiesAWellFormedChallengeAndKeepsAllOtherFailuresGeneric() {
        String id = "123e4567-e89b-42d3-a456-426614174000";
        repository.verificationAttempt = EmailVerificationRepository.VerificationAttempt.success(
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS
        );

        EmailVerified completed = service.verify(new VerifyEmailCode(id, "123456"));

        assertThat(completed.id()).isEqualTo(UUID.fromString(id));
        assertThat(completed.purpose()).isEqualTo("restricted_resource_access");
        assertThat(completed.verifiedAt()).isEqualTo(NOW);
        assertThat(repository.candidateDigest).containsOnly((byte) 3);

        repository.verificationAttempt = EmailVerificationRepository.VerificationAttempt.rejected();
        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(id, "654321")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason())
                                .isEqualTo(VerificationFailure.Reason.INVALID_OR_EXPIRED_CODE));
    }

    @Test
    void rejectsMalformedIdentifiersAndCodesBeforeOpeningARepositoryTransaction() {
        assertThatThrownBy(() -> service.verify(new VerifyEmailCode("not-a-uuid", "123456")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_ID));
        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(
                "123e4567-e89b-42d3-a456-426614174000",
                "12345"
        ))).isInstanceOfSatisfying(VerificationFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_CODE_FORMAT));
        assertThat(repository.verifiedId).isNull();
    }

    private static final class RecordingSender implements VerificationEmailSender {
        private EmailAddress recipient;
        private VerificationCode code;
        private RuntimeException failure;

        @Override
        public void send(EmailAddress recipient, VerificationCode code, Instant expiresAt) {
            if (failure != null) {
                throw failure;
            }
            this.recipient = recipient;
            this.code = code;
        }
    }

    private static final class RecordingHasher implements VerificationCodeHasher {
        private String requesterAddress;

        @Override
        public byte[] emailFingerprint(EmailAddress email) {
            return bytes(1);
        }

        @Override
        public byte[] requesterFingerprint(String requesterAddress) {
            this.requesterAddress = requesterAddress;
            return bytes(2);
        }

        @Override
        public byte[] codeDigest(UUID id, VerificationPurpose purpose, VerificationCode code) {
            return bytes(3);
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

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
