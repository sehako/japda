package io.github.sehako.japda.batch.performance.database

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer

class PerformancePostgresFixture(
	private val rootMigrationDirectory: Path,
) : AutoCloseable {
	private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
	private val iterationSequence = AtomicLong()

	val runtime: PerformancePostgresRuntime
		get() {
			check(postgres.isRunning) { "PostgreSQL Testcontainer가 시작되지 않았습니다." }
			return PerformancePostgresRuntime(postgres.containerId, POSTGRES_IMAGE)
		}

	fun start() {
		require(Files.isDirectory(rootMigrationDirectory)) {
			"API root migration 디렉터리를 찾을 수 없습니다: $rootMigrationDirectory"
		}
		if (!postgres.isRunning) postgres.start()
	}

	fun createIteration(): IterationDatabase {
		check(postgres.isRunning) { "PostgreSQL Testcontainer를 먼저 시작해야 합니다." }
		val schema = "performance_iteration_${iterationSequence.incrementAndGet()}_${UUID.randomUUID().toString().replace("-", "")}"
		createSchema(schema)
		val migrationStartedAt = Instant.now()
		val migrationResult = Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(schema)
			.defaultSchema(schema)
			.locations("filesystem:${rootMigrationDirectory.toAbsolutePath()}")
			.load()
			.migrate()
		check(migrationResult.success) { "API root migration 적용에 실패했습니다: $schema" }
		return IterationDatabase(
			schema = schema,
			jdbcUrl = postgres.jdbcUrl.withCurrentSchema(schema),
			username = postgres.username,
			password = postgres.password,
			migrationDuration = Duration.between(migrationStartedAt, Instant.now()),
		)
	}

	private fun createSchema(schema: String) {
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("CREATE SCHEMA $schema")
			}
		}
	}

	override fun close() {
		if (postgres.isRunning) postgres.stop()
	}

	private fun String.withCurrentSchema(schema: String): String =
		this + if (contains('?')) "&currentSchema=$schema" else "?currentSchema=$schema"

	private companion object {
		const val POSTGRES_IMAGE = "postgres:17-alpine"
	}
}

data class PerformancePostgresRuntime(
	val containerId: String,
	val imageName: String,
)

class IterationDatabase(
	val schema: String,
	val jdbcUrl: String,
	val username: String,
	val password: String,
	val migrationDuration: Duration,
) {
	fun datasourceProperties(): Map<String, String> = mapOf(
		"spring.datasource.url" to jdbcUrl,
		"spring.datasource.username" to username,
		"spring.datasource.password" to password,
	)

	override fun toString(): String =
		"IterationDatabase(schema=$schema, jdbcUrl=$jdbcUrl, username=[REDACTED], password=[REDACTED], migrationDuration=$migrationDuration)"
}
