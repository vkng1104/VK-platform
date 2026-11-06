package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationChallengeRepository;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationOperationGuardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    private EmailVerificationChallengeRepository challenges;
    private EmailVerificationOperationGuardRepository operationGuards;
    private RecordingTransactions transactions;
    private Clock clock;

    @BeforeEach
    void setUp() {
        challenges = mock(EmailVerificationChallengeRepository.class);
        operationGuards = mock(EmailVerificationOperationGuardRepository.class);
        transactions = new RecordingTransactions();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void locksTheGuardThenDeletesOneBoundedRetentionBatchInOneTransaction() {
        List<String> events = new ArrayList<>();
        doAnswer(invocation -> events.add("lock " + transactions.state())).when(operationGuards).lock();
        when(challenges.deleteTerminalBefore(NOW.minus(Duration.ofHours(24)), 100)).thenAnswer(invocation -> {
            events.add("delete " + transactions.state());
            return 17;
        });

        assertThat(service(Duration.ofHours(24), 100).cleanupOnce()).isEqualTo(17);

        assertThat(events).containsExactly("lock in transaction 1", "delete in transaction 1");
        assertThat(transactions.committed()).isEqualTo(1);
    }

    @Test
    void deletesOneBatchPerRun() {
        EmailVerificationCleanupService service = service(Duration.ofHours(24), 2);
        when(challenges.deleteTerminalBefore(any(), anyInt())).thenReturn(2, 1, 0);

        assertThat(List.of(service.cleanupOnce(), service.cleanupOnce(), service.cleanupOnce()))
                .containsExactly(2, 1, 0);

        verify(challenges, times(3)).deleteTerminalBefore(NOW.minus(Duration.ofHours(24)), 2);
        assertThat(transactions.committed()).isEqualTo(3);
    }

    @Test
    void deletesNothingWhenTheOperationGuardCannotBeLocked() {
        IllegalStateException missingGuard = new IllegalStateException("email verification operation guard is missing");
        doThrow(missingGuard).when(operationGuards).lock();

        assertThatThrownBy(service(Duration.ofHours(24), 100)::cleanupOnce).isSameAs(missingGuard);

        verify(challenges, never()).deleteTerminalBefore(any(), anyInt());
        assertThat(transactions.rolledBack()).isEqualTo(1);
    }

    @Test
    void rejectsUnboundedCleanupConfiguration() {
        assertThatThrownBy(() -> service(Duration.ZERO, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(Duration.ofHours(-1), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(Duration.ofHours(24), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private EmailVerificationCleanupService service(Duration retention, int batchSize) {
        return new EmailVerificationCleanupService(
                challenges,
                operationGuards,
                transactions,
                clock,
                retention,
                batchSize
        );
    }
}
