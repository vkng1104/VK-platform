package com.vkplatform.iam.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenDigestTest {
    @Test
    void restoresOnlyWellFormedDigestPlaceholders() {
        byte[] digest = TokenDigest.sha256("one-time-authorization-code");

        assertThat(TokenDigest.digestOrRestore(TokenDigest.placeholder(digest))).containsExactly(digest);
        assertThat(TokenDigest.matches(digest, TokenDigest.sha256("one-time-authorization-code"))).isTrue();
        assertThat(TokenDigest.matches(digest, TokenDigest.sha256("different-code"))).isFalse();
        assertThatThrownBy(() -> TokenDigest.digestOrRestore("sha256:broken"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyAndUnreasonablyLargeTokenValues() {
        assertThatThrownBy(() -> TokenDigest.sha256(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenDigest.sha256("x".repeat(32769)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
