package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.application.port.out.SecurityEventRepository;
import com.vkplatform.iam.identity.domain.SecurityEvent;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.SecurityEventEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaSecurityEventRepository implements SecurityEventRepository {
    private final SpringDataSecurityEventRepository events;

    JpaSecurityEventRepository(SpringDataSecurityEventRepository events) {
        this.events = events;
    }

    @Override
    @Transactional
    public void append(SecurityEvent event) {
        events.save(new SecurityEventEntity(event));
    }
}
