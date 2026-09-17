package io.github.sehako.japda.batch.performance.dataset

import io.github.sehako.japda.batch.performance.database.PerformancePostgresFixture
import io.github.sehako.japda.batch.performance.scenario.DatasetScenario
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import io.github.sehako.japda.batch.performance.scenario.SettlementJobScenario
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("정산 성능 테스트 합성 데이터 적재")
class SyntheticDatasetInserterTest {
	@Test
	@DisplayName("유효한 관계를 batch insert하고 기준 건수와 금액을 검증한다")
	fun 유효한_관계를_batch_insert하고_기준_건수와_금액을_검증한다() {
		PerformancePostgresFixture(Path.of(System.getProperty("rootMigrationDirectory"))).use { fixture ->
			fixture.start()
			val database = fixture.createIteration()
			val jdbcTemplate = JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
			val dataset = SyntheticDatasetFactory.create(SCENARIO)

			val result = SyntheticDatasetInserter().insertAndVerify(jdbcTemplate, dataset)

			assertEquals(3L, result.tableCounts.getValue("users"))
			assertEquals(3L, result.tableCounts.getValue("seller_principal_identities"))
			assertEquals(3L, result.tableCounts.getValue("products"))
			assertEquals(3L, result.tableCounts.getValue("sales"))
			assertEquals(7L, result.tableCounts.getValue("orders"))
			assertEquals(7L, result.tableCounts.getValue("payments"))
			assertEquals(70_000L, result.paymentGrossAmount)
		}
	}

	private companion object {
		val SCENARIO = PerformanceScenario(
			dataset = DatasetScenario(3, 7, 42, 10_000),
			job = SettlementJobScenario(LocalDate.of(2026, 9, 15), 1_000, Duration.ofMinutes(30)),
			warmupIterations = 0,
			measurementIterations = 1,
			resourceSamplingInterval = Duration.ofMillis(100),
		)
	}
}
