package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.api.EmailVerificationOperations;
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
import org.springframework.transaction.support.TransactionOperations;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Starts and verifies email verification challenges.
 *
 * <p>Transactions are explicit because each use case needs a boundary narrower than the method.
 * A start registers its challenge in one transaction that takes the operation guard first, then
 * sends the email and records the delivery outcome outside it, so the guard is never held while
 * the email provider is called. A verification validates its input before it opens a transaction
 * and reports a wrong code only after that transaction ends, so the failed attempt stays recorded.
 * Both join a transaction the caller already has.
 */
public final class EmailVerificationService implements EmailVerificationOperations {
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    private final EmailVerificationChallengeRepository challenges;
    private final EmailVerificationOperationGuardRepository operationGuards;
    private final TransactionOperations transactions;
    private final VerificationEmailSender sender;
    private final Clock clock;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher hasher;
    private final VerificationPolicy policy;

    public EmailVerificationService(
            EmailVerificationChallengeRepository challenges,
            EmailVerificationOperationGuardRepository operationGuards,
            TransactionOperations transactions,
            VerificationEmailSender sender,
            Clock clock,
            VerificationCodeGenerator codeGenerator,
            VerificationCodeHasher hasher,
            VerificationPolicy policy
    ) {
        this.challenges = Objects.requireNonNull(challenges);
        this.operationGuards = Objects.requireNonNull(operationGuards);
        this.transactions = Objects.requireNonNull(transactions);
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
        if (purpose != VerificationPurpose.RESTRICTED_RESOURCE_ACCESS) {
            throw VerificationFailure.invalidPurpose();
        }
        return start(email, purpose, command.requesterAddress());
    }

    @Override
    public VerificationChallengeStarted startSignIn(StartSignInEmailVerification command) {
        return start(
                new EmailAddress(command.email()),
                VerificationPurpose.SIGN_IN,
                command.requesterAddress()
        );
    }

    private VerificationChallengeStarted start(
            EmailAddress email,
            VerificationPurpose purpose,
            String requesterAddress
    ) {
        VerificationCode code = codeGenerator.generate();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(policy.challengeTtl());
        Instant resendAt = now.plus(policy.resendCooldown());

        VerificationChallenge challenge = VerificationChallenge.pending(
                id,
                purpose,
                hasher.emailFingerprint(email),
                requesterFingerprint(requesterAddress),
                hasher.codeDigest(id, purpose, code),
                policy.maxAttempts(),
                expiresAt,
                resendAt,
                now
        );
        transactions.executeWithoutResult(status -> createPending(challenge));

        try {
            sender.send(email, code, expiresAt);
        } catch (RuntimeException exception) {
            try {
                challenges.markFailed(id);
            } catch (RuntimeException markFailure) {
                exception.addSuppressed(markFailure);
            }
            throw VerificationFailure.deliveryUnavailable(exception);
        }
        challenges.markSent(id, clock.instant());

        return new VerificationChallengeStarted(id, email.masked(), expiresAt, resendAt);
    }

    /**
     * Rejects the start while any limit applies, reporting the latest time at which every limit
     * has passed. Otherwise the new challenge replaces the email's active ones of the same purpose.
     * The operation guard serializes concurrent starts so they cannot all pass the same limit.
     */
    private void createPending(VerificationChallenge challenge) {
        operationGuards.lock();

        Instant now = challenge.createdAt();
        Instant retryAt = now;
        Optional<VerificationChallenge> latest = challenges.findLatestByEmailFingerprint(challenge.emailFingerprint());
        if (latest.isPresent()) {
            retryAt = later(retryAt, latest.get().createdAt().plus(policy.resendCooldown()));
        }

        retryAt = later(retryAt, windowRetryAt(
                challenges.findNewestByEmailFingerprintCreatedSince(
                        challenge.emailFingerprint(),
                        now.minus(policy.emailWindow()),
                        policy.maxStartsPerEmail()
                ),
                policy.maxStartsPerEmail(),
                policy.emailWindow(),
                now
        ));
        retryAt = later(retryAt, windowRetryAt(
                challenges.findNewestCreatedSince(now.minus(policy.globalWindow()), policy.maxGlobalStarts()),
                policy.maxGlobalStarts(),
                policy.globalWindow(),
                now
        ));
        if (challenge.requesterFingerprint() != null) {
            retryAt = later(retryAt, windowRetryAt(
                    challenges.findNewestByRequesterFingerprintCreatedSince(
                            challenge.requesterFingerprint(),
                            now.minus(policy.requesterWindow()),
                            policy.maxStartsPerRequester()
                    ),
                    policy.maxStartsPerRequester(),
                    policy.requesterWindow(),
                    now
            ));
        }
        if (retryAt.isAfter(now)) {
            throw VerificationFailure.rateLimited(retryAt);
        }

        challenges.invalidateActive(challenge.emailFingerprint(), challenge.purpose(), now);
        challenges.create(challenge);
    }

    /**
     * A window is full when it already holds {@code limit} starts. The next start is then allowed
     * once the oldest of those starts leaves the window.
     */
    private static Instant windowRetryAt(
            List<VerificationChallenge> newestStarts,
            int limit,
            Duration window,
            Instant now
    ) {
        if (newestStarts.size() < limit) {
            return now;
        }
        return newestStarts.getLast().createdAt().plus(window);
    }

    @Override
    public EmailVerified verify(VerifyEmailCode command) {
        return verify(command, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS);
    }

    @Override
    public EmailVerified verifySignIn(VerifyEmailCode command) {
        return verify(command, VerificationPurpose.SIGN_IN);
    }

    private EmailVerified verify(VerifyEmailCode command, VerificationPurpose purpose) {
        String idValue = command.verificationId();
        if (idValue == null || !UUID_V4.matcher(idValue.strip()).matches()) {
            throw VerificationFailure.invalidId();
        }
        VerificationCode code = new VerificationCode(command.code());
        UUID id = UUID.fromString(idValue);
        Instant now = clock.instant();
        byte[] candidateDigest = hasher.codeDigest(id, purpose, code);
        Optional<VerificationChallenge> verified = transactions.execute(
                status -> attempt(id, candidateDigest, now)
        );
        if (verified == null || verified.isEmpty()) {
            throw VerificationFailure.invalidOrExpiredCode();
        }
        return new EmailVerified(
                id,
                verified.get().purpose().wireValue(),
                now,
                verified.get().emailFingerprint()
        );
    }

    /**
     * Checks one code attempt with the challenge locked. A wrong code against a challenge that
     * still accepts attempts uses up one attempt; every other rejection leaves it unchanged.
     */
    private Optional<VerificationChallenge> attempt(UUID id, byte[] candidateDigest, Instant now) {
        VerificationChallenge challenge = challenges.findLockedById(id).orElse(null);
        if (challenge == null || !acceptsAttempts(challenge, now)) {
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(challenge.otpDigest(), candidateDigest)) {
            challenges.recordFailedAttempt(challenge.failAttempt());
            return Optional.empty();
        }

        VerificationChallenge verified = challenge.verify(now);
        challenges.recordVerification(verified);
        return Optional.of(verified);
    }

    private static boolean acceptsAttempts(VerificationChallenge challenge, Instant now) {
        return challenge.deliveryStatus() == EmailDeliveryStatus.SENT
                && challenge.invalidatedAt() == null
                && challenge.verifiedAt() == null
                && now.isBefore(challenge.expiresAt())
                && challenge.attemptCount() < challenge.maxAttempts();
    }

    private byte[] requesterFingerprint(String requesterAddress) {
        if (requesterAddress == null || requesterAddress.isBlank()) {
            return null;
        }
        return hasher.requesterFingerprint(requesterAddress.strip());
    }

    private static Instant later(Instant first, Instant second) {
        return second.isAfter(first) ? second : first;
    }
}
