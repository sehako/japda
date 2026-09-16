package io.github.sehako.japda.batch.global.infrastructure.schema

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class BatchMetadataSchemaVerifier(
	private val jdbcTemplate: JdbcTemplate,
) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		verify()
	}

	fun verify() {
		REQUIRED_TABLES.forEach { table ->
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table WHERE 1 = 0", Long::class.java)
		}
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
	}
}
