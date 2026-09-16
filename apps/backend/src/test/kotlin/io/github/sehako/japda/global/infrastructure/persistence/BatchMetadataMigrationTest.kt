package io.github.sehako.japda.global.infrastructure.persistence

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Spring Batch 메타데이터 migration")
class BatchMetadataMigrationTest {
	@Test
	@DisplayName("전체 migration은 공식 메타데이터 테이블과 sequence와 외래 키를 생성한다")
	fun 전체_migration은_공식_메타데이터_구조를_생성한다() {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SCHEMA)
			.load()
			.migrate()

		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.prepareStatement(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name LIKE 'batch_%'",
			).use { statement ->
				statement.setString(1, SCHEMA)
				statement.executeQuery().use { result ->
					val tables = buildSet {
						while (result.next()) add(result.getString("table_name"))
					}
					assertEquals(REQUIRED_TABLES, tables)
				}
			}

			connection.prepareStatement(
				"SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ? AND sequence_name LIKE 'batch_%'",
			).use { statement ->
				statement.setString(1, SCHEMA)
				statement.executeQuery().use { result ->
					val sequences = buildSet {
						while (result.next()) add(result.getString("sequence_name"))
					}
					assertEquals(REQUIRED_SEQUENCES, sequences)
				}
			}

			connection.prepareStatement(
				"SELECT constraint_name FROM information_schema.table_constraints WHERE constraint_schema = ? AND constraint_type = 'FOREIGN KEY' AND constraint_name LIKE '%_fk'",
			).use { statement ->
				statement.setString(1, SCHEMA)
				statement.executeQuery().use { result ->
					val foreignKeys = buildSet {
						while (result.next()) add(result.getString("constraint_name"))
					}
					assertEquals(REQUIRED_FOREIGN_KEYS, foreignKeys)
				}
			}
		}
	}

	private companion object {
		const val SCHEMA = "batch_metadata_migration"
		val REQUIRED_TABLES = setOf(
			"batch_job_instance",
			"batch_job_execution",
			"batch_job_execution_params",
			"batch_step_execution",
			"batch_step_execution_context",
			"batch_job_execution_context",
		)
		val REQUIRED_SEQUENCES = setOf(
			"batch_step_execution_seq",
			"batch_job_execution_seq",
			"batch_job_instance_seq",
		)
		val REQUIRED_FOREIGN_KEYS = setOf(
			"job_inst_exec_fk",
			"job_exec_params_fk",
			"job_exec_step_fk",
			"step_exec_ctx_fk",
			"job_exec_ctx_fk",
		)

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
