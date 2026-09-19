package io.github.sehako.japda.batch.settlement.infrastructure.batch.reader

import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import java.time.ZoneOffset
import javax.sql.DataSource
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamException
import org.springframework.batch.infrastructure.item.support.AbstractItemStreamItemReader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

open class SettlementPaymentKeysetReader(
	dataSource: DataSource,
	private val dateRange: SettlementDateRange,
	val pageSize: Int,
	val fetchSize: Int,
	private val partitionStartInclusive: Long = Long.MIN_VALUE,
	private val partitionEndExclusive: Long = Long.MAX_VALUE,
	private val partitionEndInclusive: Boolean = true,
) : AbstractItemStreamItemReader<SettlementPaymentProjection>() {
	private val jdbcTemplate = JdbcTemplate(dataSource).apply {
		setFetchSize(fetchSize)
		maxRows = pageSize
	}
	private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
	private var page = emptyList<SettlementPaymentProjection>()
	private var pageIndex = 0
	private var lastReturnedPaymentId: Long? = null

	init {
		require(pageSize > 0) { "판매자 일일 정산 page 크기는 양수여야 합니다." }
		require(fetchSize > 0) { "판매자 일일 정산 fetch 크기는 양수여야 합니다." }
		require(partitionStartInclusive < partitionEndExclusive || partitionEndInclusive && partitionStartInclusive == partitionEndExclusive) {
			"정산 결제 파티션 범위가 올바르지 않습니다."
		}
		setName(NAME)
	}

	override fun open(executionContext: ExecutionContext) {
		super.open(executionContext)
		page = emptyList()
		pageIndex = 0
		lastReturnedPaymentId = restoreCursor(executionContext)
	}

	override fun read(): SettlementPaymentProjection? {
		if (pageIndex == page.size) fetchPage()
		if (pageIndex == page.size) return null

		val projection = page[pageIndex++]
		val paymentId = projection.paymentId
		if (!contains(paymentId)) {
			throw ItemStreamException("정산 결제가 파티션 범위를 벗어났습니다: paymentId=$paymentId")
		}
		val previousCursor = lastReturnedPaymentId
		if (previousCursor != null && paymentId <= previousCursor) {
			throw ItemStreamException("정산 결제 keyset cursor가 증가하지 않습니다: paymentId=$paymentId")
		}
		lastReturnedPaymentId = paymentId
		return projection
	}

	override fun update(executionContext: ExecutionContext) {
		super.update(executionContext)
		lastReturnedPaymentId?.let { paymentId ->
			executionContext.putLong(getExecutionContextKey(LAST_PAYMENT_ID_KEY), paymentId)
			executionContext.putInt(getExecutionContextKey(CHECKPOINTED_KEY), CHECKPOINTED_VALUE)
		}
	}

	override fun close() {
		super.close()
		page = emptyList()
		pageIndex = 0
		lastReturnedPaymentId = null
	}

	private fun fetchPage() {
		val query = SettlementPaymentKeysetQuery.create(
			dateRange,
			pageSize,
			partitionStartInclusive,
			partitionEndExclusive,
			partitionEndInclusive,
			lastReturnedPaymentId,
		)
		page = namedJdbcTemplate.query(query.sql, query.parameters, ::mapProjection)
		pageIndex = 0
	}

	private fun mapProjection(resultSet: java.sql.ResultSet, rowNum: Int): SettlementPaymentProjection =
		SettlementPaymentProjection(
			paymentId = resultSet.getLong("payment_id"),
			requestedAmount = resultSet.getLong("requested_amount"),
			paymentApprovedAt = resultSet.getTimestamp("payment_approved_at").toInstant(),
			orderId = resultSet.getNullableLong("order_id"),
			orderStatus = resultSet.getString("order_status"),
			saleId = resultSet.getNullableLong("sale_id"),
			sellerId = resultSet.getNullableLong("seller_id"),
			recipientUserId = resultSet.getNullableLong("recipient_user_id"),
			quantity = resultSet.getNullableInt("quantity"),
			unitPrice = resultSet.getNullableLong("unit_price"),
			totalPrice = resultSet.getNullableLong("total_price"),
		)

	private fun restoreCursor(executionContext: ExecutionContext): Long? {
		val checkpointedKey = getExecutionContextKey(CHECKPOINTED_KEY)
		val paymentIdKey = getExecutionContextKey(LAST_PAYMENT_ID_KEY)
		val hasCheckpointed = executionContext.containsKey(checkpointedKey)
		val hasPaymentId = executionContext.containsKey(paymentIdKey)
		if (hasCheckpointed != hasPaymentId) {
			throw ItemStreamException("정산 결제 keyset checkpoint가 일부만 저장되어 있습니다.")
		}
		if (hasCheckpointed) {
			val checkpointed = try {
				executionContext.getInt(checkpointedKey)
			} catch (exception: RuntimeException) {
				throw ItemStreamException("정산 결제 keyset checkpoint sentinel을 복원할 수 없습니다.", exception)
			}
			if (checkpointed != CHECKPOINTED_VALUE) {
				throw ItemStreamException("정산 결제 keyset checkpoint sentinel 값이 올바르지 않습니다: checkpointed=$checkpointed")
			}
		}
		if (!hasPaymentId) return null
		val paymentId = try {
			executionContext.getLong(paymentIdKey)
		} catch (exception: RuntimeException) {
			throw ItemStreamException("정산 결제 keyset cursor를 복원할 수 없습니다.", exception)
		}
		if (!contains(paymentId)) throw ItemStreamException("정산 결제 keyset cursor가 파티션 범위를 벗어났습니다: paymentId=$paymentId")
		return paymentId
	}

	private fun contains(id: Long): Boolean =
		id >= partitionStartInclusive && if (partitionEndInclusive) id <= partitionEndExclusive else id < partitionEndExclusive

	private fun java.sql.ResultSet.getNullableLong(columnName: String): Long? =
		(getObject(columnName) as? Number)?.toLong()

	private fun java.sql.ResultSet.getNullableInt(columnName: String): Int? =
		(getObject(columnName) as? Number)?.toInt()

	private companion object {
		const val NAME = "settlementPaymentKeysetReader"
		const val CHECKPOINTED_KEY = "checkpointed"
		const val CHECKPOINTED_VALUE = 1
		const val LAST_PAYMENT_ID_KEY = "lastPaymentId"
	}
}

data class SettlementPaymentKeysetQuery(
	val sql: String,
	val parameters: Map<String, Any>,
) {
	companion object {
		fun create(
			dateRange: SettlementDateRange,
			pageSize: Int,
			partitionStartInclusive: Long,
			partitionEndExclusive: Long,
			partitionEndInclusive: Boolean,
			lastPaymentId: Long?,
		): SettlementPaymentKeysetQuery = SettlementPaymentKeysetQuery(
			sql = createSql(partitionEndInclusive, lastPaymentId != null),
			parameters = baseParameters(dateRange, pageSize) + mapOf(
				"partitionStartInclusive" to partitionStartInclusive,
				"partitionEndExclusive" to partitionEndExclusive,
			) + if (lastPaymentId == null) {
				emptyMap()
			} else {
				mapOf("lastPaymentId" to lastPaymentId)
			},
		)

		private fun baseParameters(dateRange: SettlementDateRange, pageSize: Int) = mapOf(
			"approvedStatus" to APPROVED_PAYMENT_STATUS,
			"startInclusive" to dateRange.startInclusive.atOffset(ZoneOffset.UTC),
			"endExclusive" to dateRange.endExclusive.atOffset(ZoneOffset.UTC),
			"pageSize" to pageSize,
		)

		private const val APPROVED_PAYMENT_STATUS = "APPROVED"

		private val BASE_SQL = """
			SELECT p.id AS payment_id,
			       p.requested_amount AS requested_amount,
			       p.approved_at AS payment_approved_at,
			       o.id AS order_id,
			       o.status AS order_status,
			       s.id AS sale_id,
			       s.seller_id AS seller_id,
			       spi.user_id AS recipient_user_id,
			       o.quantity AS quantity,
			       o.unit_price AS unit_price,
			       o.total_price AS total_price
			FROM payments p
			LEFT JOIN orders o ON o.id = p.order_id
			LEFT JOIN sales s ON s.id = o.sale_id
			LEFT JOIN seller_principal_identities spi ON spi.seller_id = s.seller_id
			WHERE p.status = :approvedStatus
			  AND p.approved_at >= :startInclusive
			  AND p.approved_at < :endExclusive
			  AND p.id >= :partitionStartInclusive
			%s
			%s
			ORDER BY p.id ASC
			LIMIT :pageSize
		""".trimIndent()

		private fun createSql(endInclusive: Boolean, hasCursor: Boolean): String = BASE_SQL.format(
			if (endInclusive) "  AND p.id <= :partitionEndExclusive" else "  AND p.id < :partitionEndExclusive",
			if (hasCursor) "  AND p.id > :lastPaymentId" else "",
		)
	}
}
