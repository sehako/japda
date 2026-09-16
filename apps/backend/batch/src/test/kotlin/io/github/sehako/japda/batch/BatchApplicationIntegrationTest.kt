package io.github.sehako.japda.batch

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("배치 애플리케이션")
class BatchApplicationIntegrationTest {
	@AfterEach
	fun 스키마를_정리한다() {
		dropSchema(SUCCESS_SCHEMA)
		dropSchema(EMPTY_SCHEMA)
	}

	@Test
	@DisplayName("API migration이 적용된 데이터베이스에서는 시작한다")
	fun API_migration이_적용된_데이터베이스에서는_시작한다() {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SUCCESS_SCHEMA)
			.locations("filesystem:${System.getProperty("rootMigrationDirectory")}")
			.load()
			.migrate()

		startApplication(SUCCESS_SCHEMA).use { context ->
			assertTrue(context.isActive)
		}
	}

	@Test
	@DisplayName("메타데이터 스키마가 없으면 생성하지 않고 시작에 실패한다")
	fun 메타데이터_스키마가_없으면_생성하지_않고_시작에_실패한다() {
		createSchema(EMPTY_SCHEMA)

		assertFails {
			startApplication(EMPTY_SCHEMA)
		}

		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.prepareStatement("SELECT to_regclass(?)").use { statement ->
				statement.setString(1, "$EMPTY_SCHEMA.batch_job_instance")
				statement.executeQuery().use { result ->
					result.next()
					assertNull(result.getString(1))
				}
			}
		}
	}

	private fun startApplication(schema: String) =
		SpringApplicationBuilder(BatchApplication::class.java)
			.web(WebApplicationType.NONE)
			.properties(
				"spring.datasource.url=${postgres.jdbcUrl}?currentSchema=$schema",
				"spring.datasource.username=${postgres.username}",
				"spring.datasource.password=${postgres.password}",
			)
			.run()

	private fun createSchema(schema: String) {
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("CREATE SCHEMA IF NOT EXISTS $schema")
			}
		}
	}

	private fun dropSchema(schema: String) {
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
			}
		}
	}

	private companion object {
		const val SUCCESS_SCHEMA = "batch_application_success"
		const val EMPTY_SCHEMA = "batch_application_empty"

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
