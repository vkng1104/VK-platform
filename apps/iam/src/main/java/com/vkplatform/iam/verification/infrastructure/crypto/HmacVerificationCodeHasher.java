package com.vkplatform.iam.verification.infrastructure.crypto;

import com.vkplatform.iam.verification.application.port.out.VerificationCodeHasher;
import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationCode;
import com.vkplatform.iam.verification.domain.VerificationPurpose;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;

public final class HmacVerificationCodeHasher implements VerificationCodeHasher {
    private final byte[] otpPepper;
    private final byte[] fingerprintKey;

    public HmacVerificationCodeHasher(byte[] otpPepper, byte[] fingerprintKey) {
        Objects.requireNonNull(otpPepper);
        Objects.requireNonNull(fingerprintKey);
        if (otpPepper.length < 32 || fingerprintKey.length < 32) {
            throw new IllegalArgumentException("email verification secrets must contain at least 32 bytes");
        }
        this.otpPepper = otpPepper.clone();
        this.fingerprintKey = fingerprintKey.clone();
    }

    @Override
    public byte[] emailFingerprint(EmailAddress email) {
        return digest(fingerprintKey, email.value());
    }

    @Override
    public byte[] requesterFingerprint(String requesterAddress) {
        return digest(fingerprintKey, requesterAddress);
    }

    @Override
    public byte[] codeDigest(UUID id, VerificationPurpose purpose, VerificationCode code) {
        return digest(otpPepper, id.toString(), purpose.wireValue(), code.value());
    }

    private static byte[] digest(byte[] key, String... values) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    mac.update((byte) 0);
                }
                mac.update(values[index].getBytes(StandardCharsets.UTF_8));
            }
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
