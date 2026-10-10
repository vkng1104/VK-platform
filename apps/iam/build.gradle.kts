plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.vkplatform"
version = "0.0.1-SNAPSHOT"
description = "VK Platform Identity and Access Management Service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

dependencies {
	annotationProcessor("org.hibernate.orm:hibernate-processor")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
	}
}

sourceSets {
	create("integrationTest") {
		java.srcDir("src/integrationTest/java")
		resources.srcDir("src/integrationTest/resources")
		compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
		runtimeClasspath += output + compileClasspath
	}
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
	description = "Runs PostgreSQL-backed IAM integration tests."
	group = "verification"
	testClassesDirs = sourceSets["integrationTest"].output.classesDirs
	classpath = sourceSets["integrationTest"].runtimeClasspath
	useJUnitPlatform()
	shouldRunAfter(tasks.test)
}

tasks.register<JavaExec>("migrate") {
	description = "Applies IAM database migrations."
	group = "application"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass = "com.vkplatform.iam.bootstrap.MigrationApplication"
}

tasks.register("generatePersistence") {
	description = "Generates and verifies the IAM JPA static metamodel."
	group = "build"
	dependsOn(tasks.compileJava)

	doLast {
		val generatedSourceDirectory = layout.buildDirectory
			.dir("generated/sources/annotationProcessor/java/main")
			.get()
			.asFile
		val expectedMetamodels = listOf(
			"com/vkplatform/iam/verification/infrastructure/persistence/entity/EmailVerificationChallengeEntity_.java",
			"com/vkplatform/iam/verification/infrastructure/persistence/entity/EmailVerificationOperationGuardEntity_.java"
		)
		val missingMetamodels = expectedMetamodels.filterNot {
			generatedSourceDirectory.resolve(it).isFile
		}
		check(missingMetamodels.isEmpty()) {
			"Missing generated JPA metamodel sources: ${missingMetamodels.joinToString()}"
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
