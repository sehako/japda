package io.github.sehako.japda.batch.performance.dataset

import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class SyntheticDatasetInserter {
	fun insertAndVerify(
		jdbcTemplate: JdbcTemplate,
		dataset: SyntheticDataset,
		generationBatchSize: Int,
	): DatasetPreparationResult {
		require(generationBatchSize > 0) { "generationBatchSize는 양수여야 합니다." }
		val namedJdbc = NamedParameterJdbcTemplate(jdbcTemplate)
		val dataSource = requireNotNull(jdbcTemplate.dataSource) { "JdbcTemplate DataSource가 필요합니다." }
		val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
		val commonParameters = commonParameters(dataset)

		transaction.executeWithoutResult {
			namedJdbc.update(sql("insert-sale-day.sql"), commonParameters.addRange(1L, 1L))
		}
		forEachRange(dataset.sellerCount.toLong(), generationBatchSize) { startId, endId ->
			transaction.executeWithoutResult {
				val parameters = commonParameters.addRange(startId, endId)
				namedJdbc.update(sql("insert-users.sql"), parameters)
				namedJdbc.update(sql("insert-seller-identities.sql"), parameters)
				namedJdbc.update(sql("insert-products.sql"), parameters)
				namedJdbc.update(sql("insert-sales.sql"), parameters)
			}
		}
		forEachRange(dataset.orderCount.toLong(), generationBatchSize) { startId, endId ->
			transaction.executeWithoutResult {
				val parameters = commonParameters.addRange(startId, endId)
				namedJdbc.update(sql("insert-orders.sql"), parameters)
				namedJdbc.update(sql("insert-payments.sql"), parameters)
			}
		}

		return verify(jdbcTemplate, dataset)
	}

	private fun commonParameters(dataset: SyntheticDataset) = MapSqlParameterSource()
		.addValue("saleDate", dataset.saleDate)
		.addValue("sellerCount", dataset.sellerCount)
		.addValue("orderCount", dataset.orderCount)
		.addValue("randomSeed", dataset.randomSeed)
		.addValue("grossAmount", dataset.grossAmount)
		.addValue("entityCreatedAt", dataset.entityCreatedAt.atOffset(ZoneOffset.UTC))
		.addValue("orderCreatedAt", dataset.orderCreatedAt.atOffset(ZoneOffset.UTC))
		.addValue("orderExpiresAt", dataset.orderCreatedAt.plusSeconds(600).atOffset(ZoneOffset.UTC))
		.addValue("approvedAt", dataset.approvedAt.atOffset(ZoneOffset.UTC))

	private fun MapSqlParameterSource.addRange(startId: Long, endId: Long) =
		addValue("startId", startId).addValue("endId", endId)

	private fun forEachRange(totalCount: Long, batchSize: Int, action: (Long, Long) -> Unit) {
		var startId = 1L
		while (startId <= totalCount) {
			val endId = minOf(totalCount, Math.addExact(startId, batchSize.toLong() - 1L))
			action(startId, endId)
			startId = Math.addExact(endId, 1L)
		}
	}

	private fun verify(jdbc: JdbcTemplate, dataset: SyntheticDataset): DatasetPreparationResult {
		val expectedCounts = linkedMapOf(
			"users" to dataset.sellerCount.toLong(),
			"seller_principal_identities" to dataset.sellerCount.toLong(),
			"products" to dataset.sellerCount.toLong(),
			"sales" to dataset.sellerCount.toLong(),
			"orders" to dataset.orderCount.toLong(),
			"payments" to dataset.orderCount.toLong(),
		)
		val actualCounts = expectedCounts.mapValues { (table, _) ->
			jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!
		}
		val paymentGrossAmount = jdbc.queryForObject(
			"SELECT COALESCE(SUM(requested_amount), 0) FROM payments WHERE status = 'APPROVED'",
			Long::class.java,
		)!!
		if (actualCounts != expectedCounts || paymentGrossAmount != dataset.expectedSettlement.grossAmount) {
			throw DatasetPreparationException(
				"합성 데이터 사전 검증에 실패했습니다: expectedCounts=$expectedCounts, actualCounts=$actualCounts, " +
					"expectedGross=${dataset.expectedSettlement.grossAmount}, actualGross=$paymentGrossAmount",
			)
		}
		return DatasetPreparationResult(actualCounts, paymentGrossAmount)
	}

	private fun sql(name: String): String = SQL_RESOURCES.getValue(name)

	private companion object {
		val SQL_RESOURCES = listOf(
			"insert-sale-day.sql",
			"insert-users.sql",
			"insert-seller-identities.sql",
			"insert-products.sql",
			"insert-sales.sql",
			"insert-orders.sql",
			"insert-payments.sql",
		).associateWith { name ->
			ClassPathResource("dataset/$name").getContentAsString(StandardCharsets.UTF_8)
		}
	}
}

data class DatasetPreparationResult(
	val tableCounts: Map<String, Long>,
	val paymentGrossAmount: Long,
)

class DatasetPreparationException(message: String) : IllegalStateException(message)
