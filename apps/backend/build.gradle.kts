import java.util.Base64

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
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

val asciidoctorRuntime = configurations.create("asciidoctorRuntime")
val snippetsDir = layout.buildDirectory.dir("generated-snippets")
val asciidoctorOutputDir = layout.buildDirectory.dir("docs/asciidoc")

dependencies {
	implementation(platform("software.amazon.awssdk:bom:2.54.9"))
	implementation("software.amazon.awssdk:s3")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("org.postgresql:postgresql")

	asciidoctorRuntime("org.asciidoctor:asciidoctorj-cli:3.0.0")
	asciidoctorRuntime("org.springframework.restdocs:spring-restdocs-asciidoctor")

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
	environment("GOOGLE_CLIENT_ID", "synthetic-client-id")
	environment("GOOGLE_CLIENT_SECRET", "synthetic-client-secret")
	environment("AUTH_JWT_SIGNING_KEY", Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
}

val asciidoctor = tasks.register<JavaExec>("asciidoctor") {
	group = "documentation"
	description = "Asciidoctor 문서를 생성한다."
	dependsOn(tasks.test)
	inputs.dir(snippetsDir)
	inputs.dir(layout.projectDirectory.dir("src/docs/asciidoc"))
	outputs.dir(asciidoctorOutputDir)
	classpath = asciidoctorRuntime
	mainClass.set("org.asciidoctor.cli.jruby.AsciidoctorInvoker")
	jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
	args(
		"-D",
		asciidoctorOutputDir.get().asFile.absolutePath,
		"-a",
		"snippets=${snippetsDir.get().asFile.absolutePath}",
		"-a",
		"gradle-projectdir=${layout.projectDirectory.asFile.absolutePath}",
		layout.projectDirectory.file("src/docs/asciidoc/index.adoc").asFile.absolutePath,
	)
}

tasks.bootJar {
	dependsOn(asciidoctor)
	from(asciidoctorOutputDir) {
		into("BOOT-INF/classes/static/docs")
	}
}
