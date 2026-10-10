package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    @Test
    void deletesOneBoundedRetentionBatch() {
        RecordingRepository repository = new RecordingRepository();
        repository.deleted = 17;
        EmailVerificationCleanupService service = new EmailVerificationCleanupService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(24),
                100
        );

        assertThat(service.cleanupOnce()).isEqualTo(17);
        assertThat(repository.cutoff).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(repository.batchSize).isEqualTo(100);
    }

    @Test
    void rejectsUnboundedCleanupConfiguration() {
        RecordingRepository repository = new RecordingRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new EmailVerificationCleanupService(
                repository,
                clock,
                Duration.ZERO,
                100
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailVerificationCleanupService(
                repository,
                clock,
                Duration.ofHours(24),
                0
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingRepository implements EmailVerificationRepository {
        private Instant cutoff;
        private int batchSize;
        private int deleted;

        @Override
        public void createPending(VerificationChallenge challenge, VerificationPolicy policy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSent(UUID id, Instant sentAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markFailed(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerificationAttempt verify(UUID id, byte[] candidateDigest, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteTerminalBefore(Instant cutoff, int batchSize) {
            this.cutoff = cutoff;
            this.batchSize = batchSize;
            return deleted;
        }
    }
}
