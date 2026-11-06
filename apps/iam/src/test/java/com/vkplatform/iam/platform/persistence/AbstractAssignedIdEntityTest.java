package com.vkplatform.iam.platform.persistence;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractAssignedIdEntityTest {
    static Stream<AbstractAssignedIdEntity<?>> entities() {
        return Stream.of(new UuidEntity(UUID.randomUUID()), new StringEntity("authorization-1"));
    }

    @ParameterizedTest
    @MethodSource("entities")
    void startsNewAndStaysPersistedOnceMarked(AbstractAssignedIdEntity<?> entity) {
        assertThat(entity.isNew()).isTrue();

        entity.markPersisted();
        assertThat(entity.isNew()).isFalse();

        entity.markPersisted();
        assertThat(entity.isNew()).isFalse();
    }

    private static final class UuidEntity extends AbstractAssignedIdEntity<UUID> {
        private final UUID id;

        private UuidEntity(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getId() {
            return id;
        }
    }

    private static final class StringEntity extends AbstractAssignedIdEntity<String> {
        private final String id;

        private StringEntity(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }
    }
}
