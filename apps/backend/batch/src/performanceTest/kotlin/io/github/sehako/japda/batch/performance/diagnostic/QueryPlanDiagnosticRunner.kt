package io.github.sehako.japda.batch.performance.diagnostic

import io.github.sehako.japda.batch.settlement.domain.model.IdPartitionPlan
import io.github.sehako.japda.batch.settlement.domain.model.IdPartitionRange
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SettlementPaymentKeysetQuery
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

data class QueryPlanDiagnostic(
	val partitionLabel: String,
	val partitionStartInclusive: Long,
	val partitionEndExclusive: Long,
	val partitionEndInclusive: Boolean,
	val cursorPosition: String,
	val executionMillis: Long,
	val plan: String,
	val returnedRowCount: Int = 0,
)

private data class DiagnosticCursor(val approvedAt: Instant, val entryId: Long)

class QueryPlanDiagnosticRunner(
	private val jdbcTemplate: JdbcTemplate,
) {
	private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

	fun diagnose(dateRange: SettlementDateRange, pageSize: Int, partitionCount: Int): List<QueryPlanDiagnostic> {
		require(pageSize > 0) { "query plan 진단 page 크기는 양수여야 합니다." }
		require(partitionCount > 0) { "query plan 진단 파티션 수는 양수여야 합니다." }
		val bounds = settlementEntryIdBounds(dateRange)
		check(bounds.first != null && bounds.second != null) { "query plan 진단 대상 정산 원천이 없습니다." }
		val plan = IdPartitionPlan.create(bounds.first, bounds.second, partitionCount)
		val representativeIndexes = listOf(0, plan.ranges.lastIndex / 2, plan.ranges.lastIndex).distinct()
		return representativeIndexes.flatMap { index ->
			val range = plan.ranges[index]
			val label = "collectionPartition${index.toString().padStart(3, '0')}"
			val count = countSettlementEntries(dateRange, range)
			check(count > 0) { "query plan 진단 대상 정산 원천 파티션이 비어 있습니다: partition=$label" }
			val middleCursor = cursorAt(dateRange, range, count / 2)
			val lastCursor = cursorAt(dateRange, range, maxOf(count - pageSize - 1L, 0L))
			listOf(
				diagnose(label, range, "initial", query(dateRange, range, pageSize, null), null),
				diagnose(label, range, "middle", query(dateRange, range, pageSize, middleCursor.entryId), middleCursor),
				diagnose(label, range, "last", query(dateRange, range, pageSize, lastCursor.entryId), lastCursor),
			)
		}
	}

	private fun diagnose(
		partitionLabel: String,
		range: IdPartitionRange,
		cursorPosition: String,
		query: SettlementPaymentKeysetQuery,
		cursor: DiagnosticCursor?,
	): QueryPlanDiagnostic {
		val startedAt = System.nanoTime()
		val plan = namedJdbcTemplate.query(
			"EXPLAIN (ANALYZE, BUFFERS, WAL) ${query.sql}",
			query.parameters,
		) { resultSet, _ -> resultSet.getString(1) }.joinToString("\n")
		val executionMillis = (System.nanoTime() - startedAt) / 1_000_000
		val returnedRowCount = namedJdbcTemplate.query(query.sql, query.parameters) { _, _ -> Unit }.size
		return QueryPlanDiagnostic(
			partitionLabel = partitionLabel,
			partitionStartInclusive = range.startInclusive,
			partitionEndExclusive = range.endExclusive,
			partitionEndInclusive = range.endInclusive,
			cursorPosition = cursorPosition,
			executionMillis = executionMillis,
			plan = sanitize(plan, cursor),
			returnedRowCount = returnedRowCount,
		)
	}

	private fun settlementEntryIdBounds(dateRange: SettlementDateRange): Pair<Long?, Long?> = namedJdbcTemplate.queryForObject(
		"""
		SELECT MIN(id), MAX(id)
		FROM settlement_entries
		WHERE settlement_date = :settlementDate
		""".trimIndent(),
		dateParameters(dateRange),
	) { resultSet, _ ->
		(resultSet.getObject(1) as? Number)?.toLong() to (resultSet.getObject(2) as? Number)?.toLong()
	}

	private fun countSettlementEntries(dateRange: SettlementDateRange, range: IdPartitionRange): Long = namedJdbcTemplate.queryForObject(
		"""
		SELECT COUNT(*)
		FROM settlement_entries
		WHERE settlement_date = :settlementDate
		  AND id >= :partitionStartInclusive
		  AND ${if (range.endInclusive) "id <= :partitionEndExclusive" else "id < :partitionEndExclusive"}
		""".trimIndent(),
		dateParameters(dateRange) + rangeParameters(range),
		Long::class.java,
	)!!

	private fun cursorAt(dateRange: SettlementDateRange, range: IdPartitionRange, offset: Long): DiagnosticCursor = namedJdbcTemplate.queryForObject(
		"""
		SELECT payment_approved_at, id
		FROM settlement_entries
		WHERE settlement_date = :settlementDate
		  AND id >= :partitionStartInclusive
		  AND ${if (range.endInclusive) "id <= :partitionEndExclusive" else "id < :partitionEndExclusive"}
		ORDER BY id ASC
		LIMIT 1 OFFSET :offset
		""".trimIndent(),
		dateParameters(dateRange) + rangeParameters(range) + mapOf("offset" to offset),
	) { resultSet, _ -> DiagnosticCursor(resultSet.getTimestamp(1).toInstant(), resultSet.getLong(2)) }

	private fun query(dateRange: SettlementDateRange, range: IdPartitionRange, pageSize: Int, lastEntryId: Long?) =
		SettlementPaymentKeysetQuery.create(
			dateRange,
			pageSize,
			range.startInclusive,
			range.endExclusive,
			range.endInclusive,
			lastEntryId,
		)

	private fun rangeParameters(range: IdPartitionRange) = mapOf(
		"partitionStartInclusive" to range.startInclusive,
		"partitionEndExclusive" to range.endExclusive,
	)

	private fun dateParameters(dateRange: SettlementDateRange) = mapOf(
		"settlementDate" to dateRange.startInclusive.atZone(SEOUL_ZONE).toLocalDate(),
	)

	private fun sanitize(plan: String, cursor: DiagnosticCursor?): String {
		if (cursor == null) return plan
		return plan
			.replace(cursor.entryId.toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.atOffset(ZoneOffset.UTC).toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.toString().replace('T', ' '), "[REDACTED_CURSOR]")
			.replace(Regex("${cursor.approvedAt.toString().substringBefore('T')} [0-9:.]+\\+00"), "[REDACTED_CURSOR]")
	}

	private companion object {
		val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
	}
}
