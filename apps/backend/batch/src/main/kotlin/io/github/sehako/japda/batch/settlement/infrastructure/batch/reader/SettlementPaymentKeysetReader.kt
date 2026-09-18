package io.github.sehako.japda.batch.settlement.infrastructure.batch.reader

import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import java.time.Instant
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
) : AbstractItemStreamItemReader<SettlementPaymentProjection>() {
	private val jdbcTemplate = JdbcTemplate(dataSource).apply {
		setFetchSize(fetchSize)
		maxRows = pageSize
	}
	private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
	private var page = emptyList<SettlementPaymentProjection>()
	private var pageIndex = 0
	private var lastReturnedCursor: Cursor? = null

	init {
		require(pageSize > 0) { "판매자 일일 정산 page 크기는 양수여야 합니다." }
		require(fetchSize > 0) { "판매자 일일 정산 fetch 크기는 양수여야 합니다." }
		setName(NAME)
	}

	override fun open(executionContext: ExecutionContext) {
		super.open(executionContext)
		page = emptyList()
		pageIndex = 0
		lastReturnedCursor = restoreCursor(executionContext)
	}

	override fun read(): SettlementPaymentProjection? {
		if (pageIndex == page.size) fetchPage()
		if (pageIndex == page.size) return null

		val projection = page[pageIndex++]
		val cursor = Cursor(projection.paymentApprovedAt, projection.paymentId)
		val previousCursor = lastReturnedCursor
		if (previousCursor != null && cursor <= previousCursor) {
			throw ItemStreamException("정산 결제 keyset cursor가 증가하지 않습니다: cursor=$cursor")
		}
		lastReturnedCursor = cursor
		return projection
	}

	override fun update(executionContext: ExecutionContext) {
		super.update(executionContext)
		lastReturnedCursor?.let { cursor ->
			executionContext.putString(getExecutionContextKey(LAST_APPROVED_AT_KEY), cursor.approvedAt.toString())
			executionContext.putLong(getExecutionContextKey(LAST_PAYMENT_ID_KEY), cursor.paymentId)
		}
	}

	override fun close() {
		super.close()
		page = emptyList()
		pageIndex = 0
		lastReturnedCursor = null
	}

	private fun fetchPage() {
		val cursor = lastReturnedCursor
		val query = SettlementPaymentKeysetQuery.create(dateRange, pageSize, cursor)
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

	private fun restoreCursor(executionContext: ExecutionContext): Cursor? {
		val approvedAtKey = getExecutionContextKey(LAST_APPROVED_AT_KEY)
		val paymentIdKey = getExecutionContextKey(LAST_PAYMENT_ID_KEY)
		val hasApprovedAt = executionContext.containsKey(approvedAtKey)
		val hasPaymentId = executionContext.containsKey(paymentIdKey)
		if (hasApprovedAt != hasPaymentId) {
			throw ItemStreamException("정산 결제 keyset cursor가 일부만 저장되어 있습니다.")
		}
		if (!hasApprovedAt) return null

		return try {
			Cursor(
				Instant.parse(executionContext.getString(approvedAtKey)),
				executionContext.getLong(paymentIdKey),
			)
		} catch (exception: RuntimeException) {
			throw ItemStreamException("정산 결제 keyset cursor를 복원할 수 없습니다.", exception)
		}
	}

	private fun java.sql.ResultSet.getNullableLong(columnName: String): Long? =
		(getObject(columnName) as? Number)?.toLong()

	private fun java.sql.ResultSet.getNullableInt(columnName: String): Int? =
		(getObject(columnName) as? Number)?.toInt()

	private typealias Cursor = SettlementPaymentKeysetCursor

	private companion object {
		const val NAME = "settlementPaymentKeysetReader"
		const val LAST_APPROVED_AT_KEY = "lastApprovedAt"
		const val LAST_PAYMENT_ID_KEY = "lastPaymentId"
	}
}

data class SettlementPaymentKeysetCursor(
	val approvedAt: Instant,
	val paymentId: Long,
) : Comparable<SettlementPaymentKeysetCursor> {
	override fun compareTo(other: SettlementPaymentKeysetCursor): Int =
		compareValuesBy(this, other, SettlementPaymentKeysetCursor::approvedAt, SettlementPaymentKeysetCursor::paymentId)
}

data class SettlementPaymentKeysetQuery(
	val sql: String,
	val parameters: Map<String, Any>,
) {
	companion object {
		fun create(
			dateRange: SettlementDateRange,
			pageSize: Int,
			cursor: SettlementPaymentKeysetCursor?,
		): SettlementPaymentKeysetQuery = SettlementPaymentKeysetQuery(
			sql = if (cursor == null) FIRST_PAGE_SQL else NEXT_PAGE_SQL,
			parameters = baseParameters(dateRange, pageSize) + if (cursor == null) {
				emptyMap()
			} else {
				mapOf(
					"lastApprovedAt" to cursor.approvedAt.atOffset(ZoneOffset.UTC),
					"lastPaymentId" to cursor.paymentId,
				)
			},
		)

		private fun baseParameters(dateRange: SettlementDateRange, pageSize: Int) = mapOf(
			"approvedStatus" to APPROVED_PAYMENT_STATUS,
			"startInclusive" to dateRange.startInclusive.atOffset(ZoneOffset.UTC),
			"endExclusive" to dateRange.endExclusive.atOffset(ZoneOffset.UTC),
			"pageSize" to pageSize,
		)

		private const val APPROVED_PAYMENT_STATUS = "APPROVED"

		private val FIRST_PAGE_SQL = """
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
			ORDER BY p.approved_at ASC, p.id ASC
			LIMIT :pageSize
		""".trimIndent()

		private val NEXT_PAGE_SQL = FIRST_PAGE_SQL.replace(
			"ORDER BY p.approved_at ASC, p.id ASC",
			"AND (p.approved_at, p.id) > (:lastApprovedAt, :lastPaymentId)\nORDER BY p.approved_at ASC, p.id ASC",
		)
	}
}
