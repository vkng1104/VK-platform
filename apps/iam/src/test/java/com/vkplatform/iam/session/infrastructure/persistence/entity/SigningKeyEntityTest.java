package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.SigningKeyStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyEntityTest {
    private static final Instant ACTIVATED = Instant.parse("2026-10-29T01:00:00Z");

    @Test
    void transitionsFromActiveThroughRetiringToRetiredAtThePublicationBoundary() {
        SigningKeyEntity key = key();
        Instant deadline = ACTIVATED.plusSeconds(360);

        key.beginRetirement(ACTIVATED.plusSeconds(60), deadline);

        assertThat(key.status()).isEqualTo(SigningKeyStatus.RETIRING);
        assertThat(key.publishUntil()).isEqualTo(deadline);
        assertThatThrownBy(() -> key.retire(deadline.minusNanos(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(key.retire(deadline)).isTrue();
        assertThat(key.retire(deadline.plusSeconds(1))).isFalse();
        assertThat(key.retiredAt()).isEqualTo(deadline);
    }

    @Test
    void compromiseIsTerminalIdempotentAndNeverRetiresTheKey() {
        SigningKeyEntity key = key();
        Instant compromisedAt = ACTIVATED.plusSeconds(20);

        assertThat(key.compromise(compromisedAt)).isTrue();
        assertThat(key.compromise(compromisedAt.plusSeconds(10))).isFalse();

        assertThat(key.status()).isEqualTo(SigningKeyStatus.COMPROMISED);
        assertThat(key.publishUntil()).isEqualTo(compromisedAt);
        assertThat(key.compromisedAt()).isEqualTo(compromisedAt);
        assertThat(key.retiredAt()).isNull();
    }

    @Test
    void protectsKeyMaterialWithDefensiveCopies() {
        byte[] publicKey = new byte[]{1};
        byte[] encryptedPrivateKey = new byte[]{2};
        byte[] nonce = new byte[12];
        SigningKeyEntity key = new SigningKeyEntity(
                "key-1",
                publicKey,
                encryptedPrivateKey,
                nonce,
                "v1",
                ACTIVATED
        );
        publicKey[0] = 9;
        encryptedPrivateKey[0] = 9;
        nonce[0] = 9;

        assertThat(key.publicKeyX509()).containsExactly(1);
        assertThat(key.encryptedPrivateKey()).containsExactly(2);
        assertThat(key.encryptionNonce()[0]).isZero();
    }

    private static SigningKeyEntity key() {
        return new SigningKeyEntity(
                "key-1",
                new byte[]{1},
                new byte[]{2},
                new byte[12],
                "v1",
                ACTIVATED
        );
    }
}
