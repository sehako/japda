package io.github.sehako.japda.batch.performance.dataset

import io.github.sehako.japda.batch.performance.database.PerformancePostgresFixture
import io.github.sehako.japda.batch.performance.scenario.DatasetScenario
import io.github.sehako.japda.batch.performance.scenario.ApprovalTimeDistribution
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import io.github.sehako.japda.batch.performance.scenario.SettlementJobScenario
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
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
	@DisplayName("SQL 생성 구간별 transaction으로 유효한 관계와 결정론적 식별자를 적재한다")
	fun SQL_생성_구간별_transaction으로_유효한_관계와_결정론적_식별자를_적재한다() {
		PerformancePostgresFixture(Path.of(System.getProperty("rootMigrationDirectory"))).use { fixture ->
			fixture.start()
			val database = fixture.createIteration()
			val jdbcTemplate = JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
			val dataset = SyntheticDatasetFactory.create(SCENARIO)

			val result = SyntheticDatasetInserter().insertAndVerify(jdbcTemplate, dataset, SCENARIO.dataset.generationBatchSize)

			assertEquals(
				mapOf(
					"users" to 3L,
					"seller_principal_identities" to 3L,
					"products" to 3L,
					"sales" to 3L,
					"orders" to 7L,
					"payments" to 7L,
					"settlement_entries" to 7L,
				),
				result.tableCounts,
			)
			assertEquals(70_000L, result.paymentGrossAmount)
			assertEquals(
				listOf(1L to 1L, 2L to 2L, 3L to 3L, 4L to 1L, 5L to 2L, 6L to 3L, 7L to 1L),
				jdbcTemplate.query(
					"SELECT id, sale_id FROM orders ORDER BY id",
				) { resultSet, _ -> resultSet.getLong("id") to resultSet.getLong("sale_id") },
			)
			assertEquals(
				UUID.fromString("c25073f1-50fe-503a-a962-2ce761b2c982"),
				jdbcTemplate.queryForObject("SELECT idempotency_key FROM orders WHERE id = 1", UUID::class.java),
			)
			assertEquals(
				"733e8c63-a840-48e9-4b0c-caa02ca91a61",
				jdbcTemplate.queryForObject("SELECT toss_idempotency_key FROM payments WHERE id = 1", String::class.java),
			)
			assertEquals(
				listOf(1L, 1L, 1L, 1L, 10_000L, LocalDate.of(2026, 9, 15)),
				jdbcTemplate.queryForObject(
					"""
					SELECT payment_id, order_id, sale_id, seller_id, gross_amount, settlement_date
					FROM settlement_entries
					WHERE id = 1
					""".trimIndent(),
				) { resultSet, _ ->
					listOf(
						resultSet.getLong("payment_id"),
						resultSet.getLong("order_id"),
						resultSet.getLong("sale_id"),
						resultSet.getLong("seller_id"),
						resultSet.getLong("gross_amount"),
						resultSet.getObject("settlement_date", LocalDate::class.java),
					)
				},
			)

			val orderTransactions = transactionIds(jdbcTemplate, "orders")
			val paymentTransactions = transactionIds(jdbcTemplate, "payments")
			assertEquals(4, orderTransactions.distinct().size)
			assertEquals(listOf(0, 0, 1, 1, 2, 2, 3), normalized(orderTransactions))
			assertEquals(orderTransactions, paymentTransactions)
		}
	}

	@Test
	@DisplayName("UNIFORM 분포는 정산일에 포함되고 결제 순번에 따라 증가하는 승인 시각을 적재한다")
	fun UNIFORM_분포는_정산일에_포함되고_결제_순번에_따라_증가하는_승인_시각을_적재한다() {
		PerformancePostgresFixture(Path.of(System.getProperty("rootMigrationDirectory"))).use { fixture ->
			fixture.start()
			val database = fixture.createIteration()
			val jdbcTemplate = JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
			val scenario = SCENARIO.copy(dataset = SCENARIO.dataset.copy(approvalTimeDistribution = ApprovalTimeDistribution.UNIFORM))
			val dataset = SyntheticDatasetFactory.create(scenario)

			SyntheticDatasetInserter().insertAndVerify(jdbcTemplate, dataset, scenario.dataset.generationBatchSize)

			val approvedAt = jdbcTemplate.query("SELECT approved_at FROM payments ORDER BY id") { resultSet, _ -> resultSet.getTimestamp(1).toInstant() }
			assertEquals(7, approvedAt.distinct().size)
			assertEquals(Instant.parse("2026-09-14T15:00:00Z"), approvedAt.first())
			assertEquals(Instant.parse("2026-09-15T11:34:17.142Z"), approvedAt.last())
		}
	}

	private fun transactionIds(jdbcTemplate: JdbcTemplate, table: String): List<Long> = jdbcTemplate.query(
		"SELECT xmin::text::bigint AS transaction_id FROM $table ORDER BY id",
	) { resultSet, _ -> resultSet.getLong("transaction_id") }

	private fun normalized(transactionIds: List<Long>): List<Int> {
		val indexes = linkedMapOf<Long, Int>()
		return transactionIds.map { transactionId -> indexes.getOrPut(transactionId) { indexes.size } }
	}

	private companion object {
		val SCENARIO = PerformanceScenario(
			dataset = DatasetScenario(3, 7, 2, 42, 10_000, ApprovalTimeDistribution.FIXED),
			job = SettlementJobScenario(LocalDate.of(2026, 9, 15), 1_000, Duration.ofMinutes(30)),
			warmupIterations = 0,
			measurementIterations = 1,
			resourceSamplingInterval = Duration.ofMillis(100),
		)
	}
}
