package com.vkplatform.iam;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void applicationModulesRespectDeclaredBoundaries() {
        ApplicationModules.of(IamApplication.class).verify();
    }

    @Test
    void persistenceTypesStayInsideVerificationInfrastructure() {
        noClasses()
                .that().resideOutsideOfPackage("..verification.infrastructure.persistence..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..verification.infrastructure.persistence..",
                        "jakarta.persistence..",
                        "org.springframework.data.."
                )
                .check(new ClassFileImporter().importPackages("com.vkplatform.iam.verification"));
    }

    @Test
    void identityPersistenceTypesStayInsideIdentityInfrastructure() {
        noClasses()
                .that().resideOutsideOfPackage("..identity.infrastructure.persistence..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..identity.infrastructure.persistence..",
                        "jakarta.persistence..",
                        "org.springframework.data.."
                )
                .check(new ClassFileImporter().importPackages("com.vkplatform.iam.identity"));
    }

    @Test
    void sessionPersistenceTypesStayInsideSessionInfrastructure() {
        noClasses()
                .that().resideOutsideOfPackage("..session.infrastructure.persistence..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..session.infrastructure.persistence..",
                        "jakarta.persistence..",
                        "org.springframework.data.."
                )
                .check(new ClassFileImporter().importPackages("com.vkplatform.iam.session"));
    }
}
