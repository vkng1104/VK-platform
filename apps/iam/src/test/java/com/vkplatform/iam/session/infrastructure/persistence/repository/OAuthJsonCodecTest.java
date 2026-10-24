package com.vkplatform.iam.session.infrastructure.persistence.repository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthJsonCodecTest {
    @Test
    void normalizesJdkImmutableCollectionsBeforeSecurePolymorphicRoundTrip() {
        OAuthJsonCodec codec = new OAuthJsonCodec();

        Map<String, Object> restored = codec.readMap(codec.writeMap(Map.of(
                "metadata.token.claims",
                Map.of("aud", List.of("vk-platform-api"))
        )));

        assertThat(restored).containsKey("metadata.token.claims");
        Map<?, ?> claims = (Map<?, ?>) restored.get("metadata.token.claims");
        assertThat(claims.get("aud")).isEqualTo(List.of("vk-platform-api"));
    }
}
