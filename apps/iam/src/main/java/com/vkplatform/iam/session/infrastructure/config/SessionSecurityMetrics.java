package com.vkplatform.iam.session.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public final class SessionSecurityMetrics {
    private final MeterRegistry registry;

    public SessionSecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void authorizationCodeLookup() { increment("iam.oauth.authorization_code.lookups"); }
    public void tokenIssued() { increment("iam.oauth.tokens.issued"); }
    public void refreshRotated() { increment("iam.oauth.refresh.rotations"); }
    public void refreshReplayDetected() { increment("iam.oauth.refresh.replays"); }
    public void sessionRevoked() { increment("iam.session.revocations"); }
    public void clientCredentialRotated() { increment("iam.oauth.client.credential_rotations"); }
    public void signingKeyRotated() { increment("iam.signing_key.rotations"); }
    public void jwkLookup() { increment("iam.jwk.lookups"); }
    public void unknownJwk() { increment("iam.jwk.unknown_key"); }

    public <T> T timeTokenCustomization(Supplier<T> operation) {
        return Timer.builder("iam.oauth.token.customization")
                .register(registry)
                .record(operation);
    }

    private void increment(String name) {
        registry.counter(name).increment();
    }
}
