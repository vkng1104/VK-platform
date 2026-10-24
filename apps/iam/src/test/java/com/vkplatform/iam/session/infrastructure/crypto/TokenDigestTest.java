package com.vkplatform.iam.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenDigestTest {
    @Test
    void hashesTokensAndRoundTripsOnlyInternalPlaceholders() {
        byte[] digest = TokenDigest.sha256("opaque-token-value");
        String placeholder = TokenDigest.placeholder(digest);

        assertThat(digest).hasSize(32);
        assertThat(TokenDigest.digestOrRestore(placeholder)).containsExactly(digest);
        assertThat(TokenDigest.matches(digest, TokenDigest.sha256("opaque-token-value"))).isTrue();
        assertThat(TokenDigest.matches(digest, TokenDigest.sha256("another-token"))).isFalse();
    }

    @Test
    void rejectsEmptyAndMalformedValues() {
        assertThatThrownBy(() -> TokenDigest.sha256(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenDigest.digestOrRestore("sha256:not-a-digest"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
