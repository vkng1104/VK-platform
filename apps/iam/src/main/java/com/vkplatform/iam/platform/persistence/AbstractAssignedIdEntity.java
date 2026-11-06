package com.vkplatform.iam.platform.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Newness tracking for JPA entities whose identifier the application assigns before the first save.
 *
 * <p>Spring Data's {@code save()} treats an entity with a non-null identifier as existing and merges
 * it, which costs a select before every insert. An entity that extends this class reports itself as
 * new until JPA has inserted or loaded it, so {@code save()} persists it instead.
 *
 * <p>Use it only when a freshly constructed instance always means a new row. Update an existing row
 * by loading it and changing the managed entity; never by saving a detached copy. The concrete
 * entity keeps its own {@code @Id} mapping and {@code getId()}.
 */
@MappedSuperclass
public abstract class AbstractAssignedIdEntity<ID> implements Persistable<ID> {
    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    protected void markPersisted() {
        newEntity = false;
    }
}
