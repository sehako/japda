package io.github.sehako.japda.batch.performance.diagnostic

import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SettlementPaymentKeysetCursor
import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SettlementPaymentKeysetQuery
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

data class QueryPlanDiagnostic(
	val cursorPosition: String,
	val executionMillis: Long,
	val plan: String,
	val returnedRowCount: Int = 0,
)

class QueryPlanDiagnosticRunner(
	private val jdbcTemplate: JdbcTemplate,
) {
	private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

	fun diagnose(dateRange: SettlementDateRange, pageSize: Int): List<QueryPlanDiagnostic> {
		require(pageSize > 0) { "query plan 진단 page 크기는 양수여야 합니다." }
		val count = countPayments(dateRange)
		check(count > 0) { "query plan 진단 대상 결제가 없습니다." }
		val middleCursor = cursorAt(dateRange, count / 2)
		val lastCursor = cursorAt(dateRange, maxOf(count - pageSize - 1L, 0L))
		return listOf(
			diagnose("initial", SettlementPaymentKeysetQuery.create(dateRange, pageSize, null), null),
			diagnose("middle", SettlementPaymentKeysetQuery.create(dateRange, pageSize, middleCursor), middleCursor),
			diagnose("last", SettlementPaymentKeysetQuery.create(dateRange, pageSize, lastCursor), lastCursor),
		)
	}

	private fun diagnose(
		cursorPosition: String,
		query: SettlementPaymentKeysetQuery,
		cursor: SettlementPaymentKeysetCursor?,
	): QueryPlanDiagnostic {
		val startedAt = System.nanoTime()
		val plan = namedJdbcTemplate.query(
			"EXPLAIN (ANALYZE, BUFFERS) ${query.sql}",
			query.parameters,
		) { resultSet, _ -> resultSet.getString(1) }.joinToString("\n")
		val executionMillis = (System.nanoTime() - startedAt) / 1_000_000
		val returnedRowCount = namedJdbcTemplate.query(query.sql, query.parameters) { _, _ -> Unit }.size
		return QueryPlanDiagnostic(
			cursorPosition,
			executionMillis,
			sanitize(plan, cursor),
			returnedRowCount,
		)
	}

	private fun countPayments(dateRange: SettlementDateRange): Long = namedJdbcTemplate.queryForObject(
		"""
		SELECT COUNT(*)
		FROM payments
		WHERE status = 'APPROVED'
		  AND approved_at >= :startInclusive
		  AND approved_at < :endExclusive
		""".trimIndent(),
		dateParameters(dateRange),
		Long::class.java,
	)!!

	private fun cursorAt(dateRange: SettlementDateRange, offset: Long): SettlementPaymentKeysetCursor = namedJdbcTemplate.queryForObject(
		"""
		SELECT approved_at, id
		FROM payments
		WHERE status = 'APPROVED'
		  AND approved_at >= :startInclusive
		  AND approved_at < :endExclusive
		ORDER BY approved_at ASC, id ASC
		LIMIT 1 OFFSET :offset
		""".trimIndent(),
		dateParameters(dateRange) + mapOf("offset" to offset),
	) { resultSet, _ -> SettlementPaymentKeysetCursor(resultSet.getTimestamp(1).toInstant(), resultSet.getLong(2)) }

	private fun dateParameters(dateRange: SettlementDateRange) = mapOf(
		"startInclusive" to dateRange.startInclusive.atOffset(ZoneOffset.UTC),
		"endExclusive" to dateRange.endExclusive.atOffset(ZoneOffset.UTC),
	)

	private fun sanitize(plan: String, cursor: SettlementPaymentKeysetCursor?): String {
		if (cursor == null) return plan
		return plan
			.replace(cursor.paymentId.toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.atOffset(ZoneOffset.UTC).toString(), "[REDACTED_CURSOR]")
			.replace(cursor.approvedAt.toString().replace('T', ' '), "[REDACTED_CURSOR]")
			.replace(Regex("${cursor.approvedAt.toString().substringBefore('T')} [0-9:.]+\\+00"), "[REDACTED_CURSOR]")
	}
}
