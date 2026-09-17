package io.github.sehako.japda.batch.performance.dataset

import java.sql.PreparedStatement
import java.time.ZoneOffset
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate

class SyntheticDatasetInserter(
	private val insertBatchSize: Int = 1_000,
) {
	init {
		require(insertBatchSize > 0) { "insertBatchSize는 양수여야 합니다." }
	}

	fun insertAndVerify(jdbcTemplate: JdbcTemplate, dataset: SyntheticDataset): DatasetPreparationResult {
		jdbcTemplate.update(
			"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, ?, ?)",
			dataset.saleDate,
			dataset.saleDayCapacity,
			dataset.sellers.size,
		)
		insertUsers(jdbcTemplate, dataset.sellers)
		insertSellerIdentities(jdbcTemplate, dataset.sellers)
		insertProducts(jdbcTemplate, dataset.sellers)
		insertSales(jdbcTemplate, dataset)
		insertOrders(jdbcTemplate, dataset.orders)
		insertPayments(jdbcTemplate, dataset.payments)
		return verify(jdbcTemplate, dataset)
	}

	private fun insertUsers(jdbc: JdbcTemplate, sellers: List<SyntheticSeller>) = sellers.chunked(insertBatchSize).forEach { rows ->
		jdbc.batchUpdate(
			"INSERT INTO users (id, provider, provider_subject, email, created_at) VALUES (?, 'GOOGLE', ?, ?, ?)",
			setter(rows) { statement, row ->
				statement.setLong(1, row.userId)
				statement.setString(2, row.providerSubject)
				statement.setString(3, row.email)
				statement.setObject(4, row.createdAt.atOffset(ZoneOffset.UTC))
			},
		)
	}

	private fun insertSellerIdentities(jdbc: JdbcTemplate, sellers: List<SyntheticSeller>) = sellers.chunked(insertBatchSize).forEach { rows ->
		jdbc.batchUpdate(
			"INSERT INTO seller_principal_identities (user_id, seller_id) VALUES (?, ?)",
			setter(rows) { statement, row ->
				statement.setLong(1, row.userId)
				statement.setLong(2, row.sellerId)
			},
		)
	}

	private fun insertProducts(jdbc: JdbcTemplate, sellers: List<SyntheticSeller>) = sellers.chunked(insertBatchSize).forEach { rows ->
		jdbc.batchUpdate(
			"INSERT INTO products (id, seller_id, name, status, created_at) VALUES (?, ?, ?, 'READY', ?)",
			setter(rows) { statement, row ->
				statement.setLong(1, row.productId)
				statement.setLong(2, row.sellerId)
				statement.setString(3, "성능 테스트 상품-${row.productId}")
				statement.setObject(4, row.createdAt.atOffset(ZoneOffset.UTC))
			},
		)
	}

	private fun insertSales(jdbc: JdbcTemplate, dataset: SyntheticDataset) {
		val quantities = dataset.orders.groupingBy { it.sellerId }.eachCount()
		dataset.sellers.chunked(insertBatchSize).forEach { rows ->
			jdbc.batchUpdate(
				"INSERT INTO sales (id, product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
				setter(rows) { statement, row ->
					statement.setLong(1, row.saleId)
					statement.setLong(2, row.productId)
					statement.setLong(3, row.sellerId)
					statement.setObject(4, dataset.saleDate)
					statement.setLong(5, dataset.orders.first().grossAmount)
					statement.setInt(6, quantities.getValue(row.sellerId))
					statement.setObject(7, row.createdAt.atOffset(ZoneOffset.UTC))
				},
			)
		}
	}

	private fun insertOrders(jdbc: JdbcTemplate, orders: List<SyntheticOrder>) = orders.chunked(insertBatchSize).forEach { rows ->
		jdbc.batchUpdate(
			"""
			INSERT INTO orders (
				id, sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price,
				status, recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
			) VALUES (?, ?, ?, ?, ?, 1, '성능 테스트 상품', ?, ?, 'PAID', '수령인', '010-0000-0000', '00000', '주소', '상세', ?, ?)
			""".trimIndent(),
			setter(rows) { statement, row ->
				statement.setLong(1, row.id)
				statement.setLong(2, row.saleId)
				statement.setLong(3, row.buyerId)
				statement.setObject(4, row.idempotencyKey)
				statement.setString(5, row.paymentOrderId)
				statement.setLong(6, row.grossAmount)
				statement.setLong(7, row.grossAmount)
				statement.setObject(8, row.createdAt.atOffset(ZoneOffset.UTC))
				statement.setObject(9, row.expiresAt.atOffset(ZoneOffset.UTC))
			},
		)
	}

	private fun insertPayments(jdbc: JdbcTemplate, payments: List<SyntheticPayment>) = payments.chunked(insertBatchSize).forEach { rows ->
		jdbc.batchUpdate(
			"""
			INSERT INTO payments (
				id, order_id, payment_key, toss_idempotency_key, status, requested_amount, created_at, approved_at
			) VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, ?)
			""".trimIndent(),
			setter(rows) { statement, row ->
				statement.setLong(1, row.id)
				statement.setLong(2, row.orderId)
				statement.setString(3, row.paymentKey)
				statement.setString(4, row.tossIdempotencyKey)
				statement.setLong(5, row.grossAmount)
				statement.setObject(6, row.createdAt.atOffset(ZoneOffset.UTC))
				statement.setObject(7, row.approvedAt.atOffset(ZoneOffset.UTC))
			},
		)
	}

	private fun verify(jdbc: JdbcTemplate, dataset: SyntheticDataset): DatasetPreparationResult {
		val expectedCounts = linkedMapOf(
			"users" to dataset.sellers.size.toLong(),
			"seller_principal_identities" to dataset.sellers.size.toLong(),
			"products" to dataset.sellers.size.toLong(),
			"sales" to dataset.sellers.size.toLong(),
			"orders" to dataset.orders.size.toLong(),
			"payments" to dataset.payments.size.toLong(),
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

	private fun <T> setter(rows: List<T>, bind: (PreparedStatement, T) -> Unit) =
		object : BatchPreparedStatementSetter {
			override fun getBatchSize(): Int = rows.size

			override fun setValues(statement: PreparedStatement, index: Int) = bind(statement, rows[index])
		}
}

data class DatasetPreparationResult(
	val tableCounts: Map<String, Long>,
	val paymentGrossAmount: Long,
)

class DatasetPreparationException(message: String) : IllegalStateException(message)
