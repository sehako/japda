package io.github.sehako.japda.batch.settlement.infrastructure.batch.reader

import javax.sql.DataSource
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamException
import org.springframework.batch.infrastructure.item.support.AbstractItemStreamItemReader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

open class SellerSettlementIdKeysetReader(
	dataSource: DataSource,
	private val settlementRunId: Long,
	val pageSize: Int,
	val fetchSize: Int,
	private val partitionStartInclusive: Long,
	private val partitionEndExclusive: Long,
	private val partitionEndInclusive: Boolean,
) : AbstractItemStreamItemReader<Long>() {
	private val jdbcTemplate = JdbcTemplate(dataSource).apply {
		setFetchSize(fetchSize)
		maxRows = pageSize
	}
	private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
	private var page = emptyList<Long>()
	private var pageIndex = 0
	private var lastReturnedId: Long? = null

	init {
		require(pageSize > 0) { "판매자별 정산 page 크기는 양수여야 합니다." }
		require(fetchSize > 0) { "판매자별 정산 fetch 크기는 양수여야 합니다." }
		require(partitionStartInclusive < partitionEndExclusive || partitionEndInclusive && partitionStartInclusive == partitionEndExclusive) {
			"판매자별 정산 파티션 범위가 올바르지 않습니다."
		}
		setName(NAME)
	}

	override fun open(executionContext: ExecutionContext) {
		super.open(executionContext)
		page = emptyList()
		pageIndex = 0
		lastReturnedId = restoreCursor(executionContext)
	}

	override fun read(): Long? {
		if (pageIndex == page.size) fetchPage()
		if (pageIndex == page.size) return null
		val id = page[pageIndex++]
		if (!contains(id)) throw ItemStreamException("판매자별 정산이 파티션 범위를 벗어났습니다: sellerSettlementId=$id")
		val previous = lastReturnedId
		if (previous != null && id <= previous) {
			throw ItemStreamException("판매자별 정산 keyset cursor가 증가하지 않습니다: sellerSettlementId=$id")
		}
		lastReturnedId = id
		return id
	}

	override fun update(executionContext: ExecutionContext) {
		super.update(executionContext)
		lastReturnedId?.let {
			executionContext.putLong(getExecutionContextKey(LAST_ID_KEY), it)
			executionContext.putInt(getExecutionContextKey(CHECKPOINTED_KEY), CHECKPOINTED_VALUE)
		}
	}

	override fun close() {
		super.close()
		page = emptyList()
		pageIndex = 0
		lastReturnedId = null
	}

	private fun fetchPage() {
		val query = SellerSettlementIdKeysetQuery.create(
			settlementRunId,
			pageSize,
			partitionStartInclusive,
			partitionEndExclusive,
			partitionEndInclusive,
			lastReturnedId,
		)
		page = namedJdbcTemplate.query(query.sql, query.parameters) { resultSet, _ -> resultSet.getLong("id") }
		pageIndex = 0
	}

	private fun restoreCursor(executionContext: ExecutionContext): Long? {
		val checkpointedKey = getExecutionContextKey(CHECKPOINTED_KEY)
		val key = getExecutionContextKey(LAST_ID_KEY)
		val hasCheckpointed = executionContext.containsKey(checkpointedKey)
		val hasId = executionContext.containsKey(key)
		if (hasCheckpointed != hasId) {
			throw ItemStreamException("판매자별 정산 keyset checkpoint가 일부만 저장되어 있습니다.")
		}
		if (hasCheckpointed) {
			val checkpointed = try {
				executionContext.getInt(checkpointedKey)
			} catch (exception: RuntimeException) {
				throw ItemStreamException("판매자별 정산 keyset checkpoint sentinel을 복원할 수 없습니다.", exception)
			}
			if (checkpointed != CHECKPOINTED_VALUE) {
				throw ItemStreamException("판매자별 정산 keyset checkpoint sentinel 값이 올바르지 않습니다: checkpointed=$checkpointed")
			}
		}
		if (!hasId) return null
		val id = try {
			executionContext.getLong(key)
		} catch (exception: RuntimeException) {
			throw ItemStreamException("판매자별 정산 keyset cursor를 복원할 수 없습니다.", exception)
		}
		if (!contains(id)) throw ItemStreamException("판매자별 정산 keyset cursor가 파티션 범위를 벗어났습니다: sellerSettlementId=$id")
		return id
	}

	private fun contains(id: Long): Boolean =
		id >= partitionStartInclusive && if (partitionEndInclusive) id <= partitionEndExclusive else id < partitionEndExclusive

	private companion object {
		const val NAME = "sellerSettlementIdKeysetReader"
		const val CHECKPOINTED_KEY = "checkpointed"
		const val CHECKPOINTED_VALUE = 1
		const val LAST_ID_KEY = "lastSellerSettlementId"
	}
}

data class SellerSettlementIdKeysetQuery(
	val sql: String,
	val parameters: Map<String, Any>,
) {
	companion object {
		fun create(
			settlementRunId: Long,
			pageSize: Int,
			partitionStartInclusive: Long,
			partitionEndExclusive: Long,
			partitionEndInclusive: Boolean,
			lastSellerSettlementId: Long?,
		): SellerSettlementIdKeysetQuery = SellerSettlementIdKeysetQuery(
			sql = BASE_SQL.format(
				if (partitionEndInclusive) "  AND id <= :partitionEndExclusive" else "  AND id < :partitionEndExclusive",
				if (lastSellerSettlementId == null) "" else "  AND id > :lastSellerSettlementId",
			),
			parameters = mapOf(
				"settlementRunId" to settlementRunId,
				"partitionStartInclusive" to partitionStartInclusive,
				"partitionEndExclusive" to partitionEndExclusive,
				"pageSize" to pageSize,
			) + if (lastSellerSettlementId == null) emptyMap() else mapOf("lastSellerSettlementId" to lastSellerSettlementId),
		)

		private val BASE_SQL = """
			SELECT id
			FROM seller_settlements
			WHERE settlement_run_id = :settlementRunId
			  AND id >= :partitionStartInclusive
			%s
			%s
			ORDER BY id ASC
			LIMIT :pageSize
		""".trimIndent()
	}
}
