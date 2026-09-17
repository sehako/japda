import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.sehako"
version = "0.0.1-SNAPSHOT"
description = "batch"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":modules:ledger"))
	implementation("org.springframework.boot:spring-boot-starter-batch")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.flywaydb:flyway-database-postgresql")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val performanceTestSourceSet = sourceSets.create("performanceTest")

configurations[performanceTestSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[performanceTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

performanceTestSourceSet.compileClasspath += sourceSets.main.get().output
performanceTestSourceSet.runtimeClasspath += sourceSets.main.get().output

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

val rootMigrationDirectory = layout.projectDirectory.dir("../src/main/resources/db/migration")

tasks.test {
	inputs.dir(rootMigrationDirectory)
	systemProperty("rootMigrationDirectory", rootMigrationDirectory.asFile.absolutePath)
}

val forwardedDatasourceSystemProperties = setOf(
	"spring.datasource.hikari.maximum-pool-size",
	"spring.datasource.hikari.minimum-idle",
	"spring.datasource.hikari.connection-timeout",
)
val forbiddenDatasourceSystemProperties = setOf(
	"spring.datasource.url",
	"spring.datasource.username",
	"spring.datasource.password",
	"DB_URL",
	"DB_USERNAME",
	"DB_PASSWORD",
)

tasks.register<Test>("performanceTest") {
	description = "판매자 일일 정산 Job 성능 테스트를 실행합니다."
	group = "verification"
	testClassesDirs = performanceTestSourceSet.output.classesDirs
	classpath = performanceTestSourceSet.runtimeClasspath
	shouldRunAfter(tasks.test)
	maxParallelForks = 1
	inputs.dir(rootMigrationDirectory)
	outputs.upToDateWhen { false }
	filter {
		includeTestsMatching("io.github.sehako.japda.batch.performance.DailySellerSettlementJobPerformanceTest")
	}

	doFirst {
		System.getProperties().stringPropertyNames()
			.filter { propertyName ->
				propertyName.startsWith("japda.performance.") || propertyName in forwardedDatasourceSystemProperties
			}
			.forEach { propertyName -> systemProperty(propertyName, System.getProperty(propertyName)) }

		val runId = DateTimeFormatter
			.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
			.withZone(ZoneOffset.UTC)
			.format(Instant.now()) + "-" + UUID.randomUUID().toString().take(8)
		val resultDirectory = layout.buildDirectory
			.dir("reports/performance/daily-seller-settlement/$runId")
			.get()
			.asFile
			.absoluteFile

		systemProperty("rootMigrationDirectory", rootMigrationDirectory.asFile.absolutePath)
		systemProperty("japda.performance.run-id", runId)
		systemProperty("japda.performance.result-directory", resultDirectory.absolutePath)
		systemProperty("junit.jupiter.execution.parallel.enabled", "false")
		logger.lifecycle("성능 테스트 실행 ID: $runId")
		logger.lifecycle("성능 테스트 결과 디렉터리: ${resultDirectory.absolutePath}")

		val forbiddenOverrides = forbiddenDatasourceSystemProperties.filter { System.getProperty(it) != null }
		check(forbiddenOverrides.isEmpty()) {
			"외부 datasource system property는 허용하지 않습니다: ${forbiddenOverrides.joinToString()}"
		}
	}

	doLast {
		val resultDirectory = file(systemProperties.getValue("japda.performance.result-directory").toString())
		val scenario = Properties().apply {
			resultDirectory.resolve("scenario.properties").reader(Charsets.UTF_8).use(::load)
		}
		val summary = Properties().apply {
			resultDirectory.resolve("summary.properties").reader(Charsets.UTF_8).use(::load)
		}
		logger.lifecycle(
			"성능 테스트 반복: warm-up=${scenario.getProperty("japda.performance.warmup-iterations")}, " +
				"measurement=${scenario.getProperty("japda.performance.measurement-iterations")}",
		)
		logger.lifecycle(
			"성능 테스트 데이터: sellers=${scenario.getProperty("japda.performance.dataset.seller-count")}, " +
				"orders=${scenario.getProperty("japda.performance.dataset.order-count")}",
		)
		logger.lifecycle(
			"성능 테스트 설정: chunk=${scenario.getProperty("japda.batch.daily-seller-settlement.chunk-size")}, " +
				"page=${scenario.getProperty("japda.batch.daily-seller-settlement.page-size")}, " +
				"fetch=${scenario.getProperty("japda.batch.daily-seller-settlement.fetch-size")}, " +
				"pool=${scenario.getProperty("spring.datasource.hikari.maximum-pool-size")}",
		)
		logger.lifecycle(
			"성능 테스트 Job: min=${summary.getProperty("job.duration.minimum.millis")}ms, " +
				"max=${summary.getProperty("job.duration.maximum.millis")}ms, " +
				"avg=${summary.getProperty("job.duration.average.millis")}ms, " +
				"throughput=${summary.getProperty("job.throughput.average.per-second")} orders/s",
		)
		logger.lifecycle(
			"성능 테스트 판정: validation=${summary.getProperty("overall.success")}, " +
				"resources=${summary.getProperty("resource.measurement.complete")}",
		)
	}
}

tasks.register<Test>("performanceTestSupport") {
	description = "판매자 일일 정산 Job 성능 테스트 지원 코드를 검증합니다."
	group = "verification"
	testClassesDirs = performanceTestSourceSet.output.classesDirs
	classpath = performanceTestSourceSet.runtimeClasspath
	shouldRunAfter(tasks.test)
	maxParallelForks = 1
	inputs.dir(rootMigrationDirectory)
	systemProperty("rootMigrationDirectory", rootMigrationDirectory.asFile.absolutePath)
	systemProperty("junit.jupiter.execution.parallel.enabled", "false")
	filter {
		excludeTestsMatching("io.github.sehako.japda.batch.performance.DailySellerSettlementJobPerformanceTest")
	}
}
