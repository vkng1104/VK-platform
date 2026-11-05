package com.vkplatform.iam;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Persistable;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.vkplatform.iam");

    @Test
    void onlyInfrastructurePersistenceUsesTheSharedPersistenceSupport() {
        noClasses()
                .that().resideOutsideOfPackages("..infrastructure.persistence..", "..platform.persistence..")
                .should().dependOnClassesThat().resideInAPackage("..platform.persistence..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void onlyTheSharedBaseImplementsPersistableDirectly() {
        noClasses()
                .that().resideOutsideOfPackage("..platform.persistence..")
                .should(directlyImplement(Persistable.class))
                .because("assigned-ID entities extend AbstractAssignedIdEntity instead of copying its newness logic")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void onlyServicesPersistenceAndWiringUseRepositoryPorts() {
        noClasses()
                .that().resideOutsideOfPackages(
                        "..application.service..",
                        "..application.port.out..",
                        "..infrastructure.persistence.."
                )
                .and().areNotAnnotatedWith(Configuration.class)
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("..application.port.out..")
                                .and(JavaClass.Predicates.simpleNameEndingWith("Repository"))
                )
                .because("adapters such as controllers, listeners and framework hooks go through an application service")
                .check(PRODUCTION_CLASSES);
    }

    private static ArchCondition<JavaClass> directlyImplement(Class<?> type) {
        return new ArchCondition<>("directly implement " + type.getSimpleName()) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean direct = javaClass.getRawInterfaces().stream()
                        .anyMatch(candidate -> candidate.isEquivalentTo(type));
                events.add(new SimpleConditionEvent(
                        javaClass,
                        direct,
                        javaClass.getName() + (direct ? " directly implements " : " does not directly implement ")
                                + type.getSimpleName()
                ));
            }
        };
    }
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
