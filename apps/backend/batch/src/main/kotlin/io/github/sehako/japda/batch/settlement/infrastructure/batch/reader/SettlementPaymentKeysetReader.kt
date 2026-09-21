package io.github.sehako.japda.batch.settlement.infrastructure.batch.reader

import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import java.time.ZoneId
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
	private var lastReturnedEntryId: Long? = null

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
		lastReturnedEntryId = restoreCursor(executionContext)
	}

	override fun read(): SettlementPaymentProjection? {
		if (pageIndex == page.size) fetchPage()
		if (pageIndex == page.size) return null

		val projection = page[pageIndex++]
		val entryId = projection.entryId
		if (!contains(entryId)) {
			throw ItemStreamException("정산 원천이 파티션 범위를 벗어났습니다: entryId=$entryId")
		}
		val previousCursor = lastReturnedEntryId
		if (previousCursor != null && entryId <= previousCursor) {
			throw ItemStreamException("정산 원천 keyset cursor가 증가하지 않습니다: entryId=$entryId")
		}
		lastReturnedEntryId = entryId
		return projection
	}

	override fun update(executionContext: ExecutionContext) {
		super.update(executionContext)
		lastReturnedEntryId?.let { entryId ->
			executionContext.putLong(getExecutionContextKey(LAST_ENTRY_ID_KEY), entryId)
			executionContext.putInt(getExecutionContextKey(CHECKPOINTED_KEY), CHECKPOINTED_VALUE)
		}
	}

	override fun close() {
		super.close()
		page = emptyList()
		pageIndex = 0
		lastReturnedEntryId = null
	}

	private fun fetchPage() {
		val query = SettlementPaymentKeysetQuery.create(
			dateRange,
			pageSize,
			partitionStartInclusive,
			partitionEndExclusive,
			partitionEndInclusive,
			lastReturnedEntryId,
		)
		page = namedJdbcTemplate.query(query.sql, query.parameters, ::mapProjection)
		pageIndex = 0
	}

	private fun mapProjection(resultSet: java.sql.ResultSet, rowNum: Int): SettlementPaymentProjection =
		SettlementPaymentProjection(
			entryId = resultSet.getLong("entry_id"),
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
		val paymentIdKey = getExecutionContextKey(LAST_ENTRY_ID_KEY)
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
		if (!contains(paymentId)) throw ItemStreamException("정산 원천 keyset cursor가 파티션 범위를 벗어났습니다: entryId=$paymentId")
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
		const val LAST_ENTRY_ID_KEY = "lastEntryId"
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
			"settlementDate" to dateRange.startInclusive.atZone(SEOUL_ZONE).toLocalDate(),
			"pageSize" to pageSize,
		)

		private val BASE_SQL = """
			SELECT se.id AS entry_id,
			       se.payment_id,
			       se.gross_amount AS requested_amount,
			       se.payment_approved_at,
			       se.order_id,
			       'PAID' AS order_status,
			       se.sale_id,
			       se.seller_id,
			       se.recipient_user_id,
			       se.quantity,
			       se.unit_price,
			       se.gross_amount AS total_price
			FROM settlement_entries se
			WHERE se.settlement_date = :settlementDate
			  AND se.id >= :partitionStartInclusive
			%s
			%s
			ORDER BY se.id ASC
			LIMIT :pageSize
		""".trimIndent()

		private fun createSql(endInclusive: Boolean, hasCursor: Boolean): String = BASE_SQL.format(
			if (endInclusive) "  AND se.id <= :partitionEndExclusive" else "  AND se.id < :partitionEndExclusive",
			if (hasCursor) "  AND se.id > :lastPaymentId" else "",
		)

		private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")
	}
}
