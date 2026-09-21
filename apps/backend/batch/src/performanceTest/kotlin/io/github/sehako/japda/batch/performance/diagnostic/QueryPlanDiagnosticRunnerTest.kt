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
	@DisplayName("실제 ID 범위를 나눈 초기·중간·마지막 파티션의 cursor별 실행계획을 기록한다")
	fun 실제_ID_범위를_나눈_대표_파티션의_cursor별_실행계획을_기록한다() {
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
			jdbcTemplate.update("DELETE FROM settlement_entries WHERE id = 7")

			val diagnostics = QueryPlanDiagnosticRunner(jdbcTemplate).diagnose(
				SettlementDateRange.from(scenario.job.settlementDate),
				pageSize = 2,
				partitionCount = 3,
			)

			assertEquals(
				listOf(
					"collectionPartition000" to "initial",
					"collectionPartition000" to "middle",
					"collectionPartition000" to "last",
					"collectionPartition001" to "initial",
					"collectionPartition001" to "middle",
					"collectionPartition001" to "last",
					"collectionPartition002" to "initial",
					"collectionPartition002" to "middle",
					"collectionPartition002" to "last",
				),
				diagnostics.map { it.partitionLabel to it.cursorPosition },
			)
			assertEquals(1L, diagnostics.first().partitionStartInclusive)
			assertEquals(3L, diagnostics.first().partitionEndExclusive)
			assertFalse(diagnostics.first().partitionEndInclusive)
			assertEquals(5L, diagnostics.last().partitionStartInclusive)
			assertEquals(7L, diagnostics.last().partitionEndExclusive)
			assertFalse(diagnostics.last().partitionEndInclusive)
			assertFalse(diagnostics.any { it.plan.contains("2026-09-15 03:00:00") })
			assertFalse(diagnostics.any { it.executionMillis < 0 })
		}
	}
}
