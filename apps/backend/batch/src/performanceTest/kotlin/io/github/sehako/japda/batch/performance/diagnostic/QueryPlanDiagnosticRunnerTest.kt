package io.github.sehako.japda.batch.performance.diagnostic

import io.github.sehako.japda.batch.performance.database.PerformancePostgresFixture
import io.github.sehako.japda.batch.performance.dataset.SyntheticDatasetFactory
import io.github.sehako.japda.batch.performance.dataset.SyntheticDatasetInserter
import io.github.sehako.japda.batch.performance.scenario.DatasetScenario
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import io.github.sehako.japda.batch.performance.scenario.SettlementJobScenario
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.DisplayName
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("정산 수집 query plan 진단")
class QueryPlanDiagnosticRunnerTest {
	@Test
	@DisplayName("초기·중간·마지막 cursor의 실행계획에서 실제 cursor 식별값을 제거한다")
	fun 초기_중간_마지막_cursor의_실행계획에서_실제_cursor_식별값을_제거한다() {
		PerformancePostgresFixture(Path.of(System.getProperty("rootMigrationDirectory"))).use { fixture ->
			fixture.start()
			val database = fixture.createIteration()
			val jdbcTemplate = JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
			val scenario = PerformanceScenario(
				dataset = DatasetScenario(3, 7, 2, 1, 10_000),
				job = SettlementJobScenario(LocalDate.of(2026, 9, 15), 1_000, Duration.ofMinutes(65)),
				warmupIterations = 0,
				measurementIterations = 1,
				resourceSamplingInterval = Duration.ofMillis(100),
			)
			SyntheticDatasetInserter().insertAndVerify(jdbcTemplate, SyntheticDatasetFactory.create(scenario), 2)

			val diagnostics = QueryPlanDiagnosticRunner(jdbcTemplate).diagnose(
				SettlementDateRange.from(scenario.job.settlementDate),
				pageSize = 2,
			)

		assertEquals(listOf("initial", "middle", "last"), diagnostics.map { it.cursorPosition })
		assertEquals(2, diagnostics.single { it.cursorPosition == "last" }.returnedRowCount)
		assertFalse(diagnostics.any { it.plan.contains("payment_id") || it.plan.contains("2026-09-15 03:00:00") })
			assertFalse(diagnostics.any { it.executionMillis < 0 })
		}
	}
}
