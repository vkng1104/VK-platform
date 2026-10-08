package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.api.EmailVerificationOperations;
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

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class EmailVerificationService implements EmailVerificationOperations {
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    private final EmailVerificationRepository repository;
    private final VerificationEmailSender sender;
    private final Clock clock;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher hasher;
    private final VerificationPolicy policy;

    public EmailVerificationService(
            EmailVerificationRepository repository,
            VerificationEmailSender sender,
            Clock clock,
            VerificationCodeGenerator codeGenerator,
            VerificationCodeHasher hasher,
            VerificationPolicy policy
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.sender = Objects.requireNonNull(sender);
        this.clock = Objects.requireNonNull(clock);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.hasher = Objects.requireNonNull(hasher);
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public VerificationChallengeStarted start(StartEmailVerification command) {
        EmailAddress email = new EmailAddress(command.email());
        VerificationPurpose purpose = VerificationPurpose.fromWireValue(command.purpose());
        VerificationCode code = codeGenerator.generate();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(policy.challengeTtl());
        Instant resendAt = now.plus(policy.resendCooldown());

        VerificationChallenge challenge = new VerificationChallenge(
                id,
                purpose,
                hasher.emailFingerprint(email),
                requesterFingerprint(command.requesterAddress()),
                hasher.codeDigest(id, purpose, code),
                policy.maxAttempts(),
                expiresAt,
                resendAt,
                now
        );
        repository.createPending(challenge, policy);

        try {
            sender.send(email, code, expiresAt);
        } catch (RuntimeException exception) {
            try {
                repository.markFailed(id);
            } catch (RuntimeException markFailure) {
                exception.addSuppressed(markFailure);
            }
            throw VerificationFailure.deliveryUnavailable(exception);
        }
        repository.markSent(id, clock.instant());

        return new VerificationChallengeStarted(id, email.masked(), expiresAt, resendAt);
    }

    @Override
    public EmailVerified verify(VerifyEmailCode command) {
        String idValue = command.verificationId();
        if (idValue == null || !UUID_V4.matcher(idValue.strip()).matches()) {
            throw VerificationFailure.invalidId();
        }
        VerificationCode code = new VerificationCode(command.code());
        UUID id = UUID.fromString(idValue);
        VerificationPurpose purpose = VerificationPurpose.RESTRICTED_RESOURCE_ACCESS;
        Instant now = clock.instant();
        EmailVerificationRepository.VerificationAttempt attempt = repository.verify(
                id,
                hasher.codeDigest(id, purpose, code),
                now
        );
        if (!attempt.verified()) {
            throw VerificationFailure.invalidOrExpiredCode();
        }
        return new EmailVerified(id, attempt.purpose().wireValue(), now);
    }

    private byte[] requesterFingerprint(String requesterAddress) {
        if (requesterAddress == null || requesterAddress.isBlank()) {
            return null;
        }
        return hasher.requesterFingerprint(requesterAddress.strip());
    }
}
