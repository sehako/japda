package io.github.sehako.japda.batch.performance.diagnostic

import io.github.sehako.japda.batch.settlement.domain.model.IdPartitionPlan
import io.github.sehako.japda.batch.settlement.domain.model.IdPartitionRange
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SettlementPaymentKeysetQuery
import java.time.Instant
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

private data class DiagnosticCursor(val approvedAt: Instant, val paymentId: Long)

class QueryPlanDiagnosticRunner(
	private val jdbcTemplate: JdbcTemplate,
) {
	private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

	fun diagnose(dateRange: SettlementDateRange, pageSize: Int, partitionCount: Int): List<QueryPlanDiagnostic> {
		require(pageSize > 0) { "query plan 진단 page 크기는 양수여야 합니다." }
		require(partitionCount > 0) { "query plan 진단 파티션 수는 양수여야 합니다." }
		val bounds = paymentIdBounds(dateRange)
		check(bounds.first != null && bounds.second != null) { "query plan 진단 대상 결제가 없습니다." }
		val plan = IdPartitionPlan.create(bounds.first, bounds.second, partitionCount)
		val representativeIndexes = listOf(0, plan.ranges.lastIndex / 2, plan.ranges.lastIndex).distinct()
		return representativeIndexes.flatMap { index ->
			val range = plan.ranges[index]
			val label = "collectionPartition${index.toString().padStart(3, '0')}"
			val count = countPayments(dateRange, range)
			check(count > 0) { "query plan 진단 대상 파티션이 비어 있습니다: partition=$label" }
			val middleCursor = cursorAt(dateRange, range, count / 2)
			val lastCursor = cursorAt(dateRange, range, maxOf(count - pageSize - 1L, 0L))
			listOf(
				diagnose(label, range, "initial", query(dateRange, range, pageSize, null), null),
				diagnose(label, range, "middle", query(dateRange, range, pageSize, middleCursor.paymentId), middleCursor),
				diagnose(label, range, "last", query(dateRange, range, pageSize, lastCursor.paymentId), lastCursor),
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
			"EXPLAIN (ANALYZE, BUFFERS) ${query.sql}",
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

	private fun paymentIdBounds(dateRange: SettlementDateRange): Pair<Long?, Long?> = namedJdbcTemplate.queryForObject(
		"""
		SELECT MIN(id), MAX(id)
		FROM payments
		WHERE status = 'APPROVED'
		  AND approved_at >= :startInclusive
		  AND approved_at < :endExclusive
		""".trimIndent(),
		dateParameters(dateRange),
	) { resultSet, _ ->
		(resultSet.getObject(1) as? Number)?.toLong() to (resultSet.getObject(2) as? Number)?.toLong()
	}

	private fun countPayments(dateRange: SettlementDateRange, range: IdPartitionRange): Long = namedJdbcTemplate.queryForObject(
		"""
		SELECT COUNT(*)
		FROM payments
		WHERE status = 'APPROVED'
		  AND approved_at >= :startInclusive
		  AND approved_at < :endExclusive
		  AND id >= :partitionStartInclusive
		  AND ${if (range.endInclusive) "id <= :partitionEndExclusive" else "id < :partitionEndExclusive"}
		""".trimIndent(),
		dateParameters(dateRange) + rangeParameters(range),
		Long::class.java,
	)!!

	private fun cursorAt(dateRange: SettlementDateRange, range: IdPartitionRange, offset: Long): DiagnosticCursor = namedJdbcTemplate.queryForObject(
		"""
		SELECT approved_at, id
		FROM payments
		WHERE status = 'APPROVED'
		  AND approved_at >= :startInclusive
		  AND approved_at < :endExclusive
		  AND id >= :partitionStartInclusive
		  AND ${if (range.endInclusive) "id <= :partitionEndExclusive" else "id < :partitionEndExclusive"}
		ORDER BY id ASC
		LIMIT 1 OFFSET :offset
		""".trimIndent(),
		dateParameters(dateRange) + rangeParameters(range) + mapOf("offset" to offset),
	) { resultSet, _ -> DiagnosticCursor(resultSet.getTimestamp(1).toInstant(), resultSet.getLong(2)) }

	private fun query(dateRange: SettlementDateRange, range: IdPartitionRange, pageSize: Int, lastPaymentId: Long?) =
		SettlementPaymentKeysetQuery.create(
			dateRange,
			pageSize,
			range.startInclusive,
			range.endExclusive,
			range.endInclusive,
			lastPaymentId,
		)

	private fun rangeParameters(range: IdPartitionRange) = mapOf(
		"partitionStartInclusive" to range.startInclusive,
		"partitionEndExclusive" to range.endExclusive,
	)

	private fun dateParameters(dateRange: SettlementDateRange) = mapOf(
		"startInclusive" to dateRange.startInclusive.atOffset(ZoneOffset.UTC),
		"endExclusive" to dateRange.endExclusive.atOffset(ZoneOffset.UTC),
	)

	private fun sanitize(plan: String, cursor: DiagnosticCursor?): String {
		if (cursor == null) return plan
		return plan
			.replace(cursor.paymentId.toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.atOffset(ZoneOffset.UTC).toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.toString().replace('T', ' '), "[REDACTED_CURSOR]")
			.replace(Regex("${cursor.approvedAt.toString().substringBefore('T')} [0-9:.]+\\+00"), "[REDACTED_CURSOR]")
	}
}
