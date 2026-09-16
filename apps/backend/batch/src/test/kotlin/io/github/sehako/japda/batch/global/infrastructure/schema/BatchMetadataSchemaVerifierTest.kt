package io.github.sehako.japda.batch.global.infrastructure.schema

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Spring Batch 메타데이터 스키마 검증기")
class BatchMetadataSchemaVerifierTest {
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun 데이터베이스를_초기화한다() {
		jdbcTemplate = JdbcTemplate(
			DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
		)
		jdbcTemplate.execute(
			"DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_CONTEXT, BATCH_STEP_EXECUTION_CONTEXT, " +
				"BATCH_STEP_EXECUTION, BATCH_JOB_EXECUTION_PARAMS, BATCH_JOB_EXECUTION, BATCH_JOB_INSTANCE CASCADE",
		)
	}

	@Test
	@DisplayName("필수 메타데이터 테이블이 없으면 검증에 실패한다")
	fun 필수_메타데이터_테이블이_없으면_검증에_실패한다() {
		val verifier = BatchMetadataSchemaVerifier(jdbcTemplate)

		assertFailsWith<BadSqlGrammarException> {
			verifier.verify()
		}
	}

	@Test
	@DisplayName("필수 메타데이터 테이블을 모두 읽을 수 있으면 검증에 성공한다")
	fun 필수_메타데이터_테이블을_모두_읽을_수_있으면_검증에_성공한다() {
		REQUIRED_TABLES.forEach { table ->
			jdbcTemplate.execute("CREATE TABLE $table (id BIGINT)")
		}
		val verifier = BatchMetadataSchemaVerifier(jdbcTemplate)

		verifier.verify()
	}

	private companion object {
		val REQUIRED_TABLES = listOf(
			"BATCH_JOB_INSTANCE",
			"BATCH_JOB_EXECUTION",
			"BATCH_JOB_EXECUTION_PARAMS",
			"BATCH_STEP_EXECUTION",
			"BATCH_STEP_EXECUTION_CONTEXT",
			"BATCH_JOB_EXECUTION_CONTEXT",
		)

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
