package com.vkplatform.iam.identity.infrastructure.crypto;

import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureGrantTokenCodecTest {
    @Test
    void generatesOpaqueUrlSafeTokensAndStableDigests() {
        SecureGrantTokenCodec codec = new SecureGrantTokenCodec(new SecureRandom());

        GrantTokenCodec.GeneratedGrant first = codec.generate();
        GrantTokenCodec.GeneratedGrant second = codec.generate();

        assertThat(first.value()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(first.digest()).hasSize(32).containsExactly(codec.digest(first.value()));
        assertThat(first.digest()).isNotEqualTo(second.digest());
    }

    @Test
    void rejectsMalformedTokensBeforeHashing() {
        SecureGrantTokenCodec codec = new SecureGrantTokenCodec(new SecureRandom());

        assertThatThrownBy(() -> codec.digest("too-short"))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT));
    }
}
