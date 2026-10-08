package com.vkplatform.iam.verification.application;

import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.IDN;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class EmailVerificationService {
    private static final int MAXIMUM_EMAIL_LENGTH = 254;
    private static final Pattern LOCAL_PART = Pattern.compile("^[^\\s@\\\"(),:;<>\\[\\]\\\\]+$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    private final EmailVerificationRepository repository;
    private final VerificationEmailSender sender;
    private final Clock clock;
    private final SecureRandom random;
    private final byte[] otpPepper;
    private final byte[] fingerprintKey;
    private final VerificationPolicy policy;

    public EmailVerificationService(
            EmailVerificationRepository repository,
            VerificationEmailSender sender,
            Clock clock,
            SecureRandom random,
            byte[] otpPepper,
            byte[] fingerprintKey,
            VerificationPolicy policy
    ) {
        if (otpPepper.length < 32 || fingerprintKey.length < 32) {
            throw new IllegalArgumentException("email verification secrets must contain at least 32 bytes");
        }
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.random = random;
        this.otpPepper = otpPepper.clone();
        this.fingerprintKey = fingerprintKey.clone();
        this.policy = policy;
    }

    public StartedVerification start(String email, String purposeValue, String requesterAddress) {
        String normalizedEmail = normalizeEmail(email);
        VerificationPurpose purpose = VerificationPurpose.fromWireValue(purposeValue);
        UUID id = UUID.randomUUID();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        Instant now = clock.instant();
        Instant expiresAt = now.plus(policy.challengeTtl());
        Instant resendAt = now.plus(policy.resendCooldown());

        VerificationChallenge challenge = new VerificationChallenge(
                id,
                purpose,
                digest(fingerprintKey, normalizedEmail),
                requesterAddress == null || requesterAddress.isBlank()
                        ? null
                        : digest(fingerprintKey, requesterAddress.strip()),
                digest(otpPepper, id.toString(), purpose.wireValue(), code),
                policy.maxAttempts(),
                expiresAt,
                resendAt,
                now
        );
        repository.createPending(challenge, policy);

        try {
            sender.send(normalizedEmail, code, expiresAt);
        } catch (RuntimeException exception) {
            try {
                repository.markFailed(id);
            } catch (RuntimeException markFailure) {
                exception.addSuppressed(markFailure);
            }
            throw VerificationFailure.deliveryUnavailable(exception);
        }
        repository.markSent(id, clock.instant());

        return new StartedVerification(id, maskEmail(normalizedEmail), expiresAt, resendAt);
    }

    public CompletedVerification verify(String idValue, String code) {
        if (idValue == null || !UUID_V4.matcher(idValue.strip()).matches()) {
            throw VerificationFailure.invalidId();
        }
        if (code == null || !CODE.matcher(code).matches()) {
            throw VerificationFailure.invalidCodeFormat();
        }

        UUID id = UUID.fromString(idValue);
        VerificationPurpose purpose = VerificationPurpose.RESTRICTED_RESOURCE_ACCESS;
        byte[] candidateDigest = digest(otpPepper, id.toString(), purpose.wireValue(), code);
        Instant now = clock.instant();
        EmailVerificationRepository.VerificationAttempt attempt = repository.verify(id, candidateDigest, now);
        if (!attempt.verified()) {
            throw VerificationFailure.invalidOrExpiredCode();
        }
        return new CompletedVerification(id, purpose, now);
    }

    private static String normalizeEmail(String value) {
        if (value == null) {
            throw VerificationFailure.invalidEmail();
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAXIMUM_EMAIL_LENGTH
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw VerificationFailure.invalidEmail();
        }
        int separator = normalized.lastIndexOf('@');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw VerificationFailure.invalidEmail();
        }
        String local = normalized.substring(0, separator);
        String domain;
        try {
            domain = IDN.toASCII(normalized.substring(separator + 1)).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw VerificationFailure.invalidEmail();
        }
        if (!LOCAL_PART.matcher(local).matches() || !DOMAIN.matcher(domain).matches()) {
            throw VerificationFailure.invalidEmail();
        }
        return local + "@" + domain;
    }

    private static String maskEmail(String email) {
        int separator = email.lastIndexOf('@');
        String local = email.substring(0, separator);
        if (local.codePointCount(0, local.length()) == 1) {
            return "*" + email.substring(separator);
        }
        int firstEnd = local.offsetByCodePoints(0, 1);
        int lastStart = local.offsetByCodePoints(0, local.codePointCount(0, local.length()) - 1);
        int hidden = local.codePointCount(firstEnd, lastStart);
        return local.substring(0, firstEnd) + "*".repeat(hidden) + local.substring(lastStart)
                + email.substring(separator);
    }

    private static byte[] digest(byte[] key, String... values) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    mac.update((byte) 0);
                }
                mac.update(values[index].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    public record StartedVerification(UUID id, String maskedEmail, Instant expiresAt, Instant resendAfter) {
    }

    public record CompletedVerification(UUID id, VerificationPurpose purpose, Instant verifiedAt) {
    }
}
