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
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("정산 원천 집계 query plan 진단")
class QueryPlanDiagnosticRunnerTest {
	@Test
	@DisplayName("정산 원천 경계 산정과 수집 집계 쿼리의 실행계획을 기록한다")
	fun 정산_원천_경계_산정과_수집_집계_쿼리의_실행계획을_기록한다() {
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
			)

			assertEquals(listOf("entryBounds", "entryAggregate"), diagnostics.map { it.label })
			assertEquals(listOf(1, 1), diagnostics.map { it.returnedRowCount })
			assertTrue(diagnostics.all { it.plan.isNotBlank() })
			assertFalse(diagnostics.any { it.executionMillis < 0 })
		}
	}
}
