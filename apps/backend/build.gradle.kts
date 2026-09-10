plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.asciidoctor.jvm.convert") version "4.0.5"
}

group = "io.github.sehako"
version = "0.0.1-SNAPSHOT"
description = "backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

val asciidoctorExt = configurations.create("asciidoctorExt")

extensions.configure<org.asciidoctor.gradle.jvm.AsciidoctorJExtension> {
	setVersion("3.0.0")
}

dependencies {
	implementation(platform("software.amazon.awssdk:bom:2.54.9"))
	implementation("software.amazon.awssdk:s3")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("org.postgresql:postgresql")

	asciidoctorExt("org.springframework.restdocs:spring-restdocs-asciidoctor")

	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	outputs.dir(layout.buildDirectory.dir("generated-snippets"))
}

tasks.asciidoctor {
	dependsOn(tasks.test)
	configurations(asciidoctorExt.name)
	inputs.dir(layout.buildDirectory.dir("generated-snippets"))
}

tasks.bootJar {
	dependsOn(tasks.asciidoctor)
	from(tasks.asciidoctor.map { it.outputDir }) {
		into("BOOT-INF/classes/static/docs")
	}
}
