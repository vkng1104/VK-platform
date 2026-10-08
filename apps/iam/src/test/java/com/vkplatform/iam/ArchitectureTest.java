package com.vkplatform.iam;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {
    @Test
    void applicationModulesRespectDeclaredBoundaries() {
        ApplicationModules.of(IamApplication.class).verify();
    }
}
