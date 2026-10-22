package io.github.sehako.japda.batch.performance.diagnostic

import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import java.time.ZoneId
import org.springframework.jdbc.core.JdbcTemplate

data class QueryPlanDiagnostic(
	val label: String,
	val executionMillis: Long,
	val plan: String,
	val returnedRowCount: Int = 0,
)

class QueryPlanDiagnosticRunner(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun diagnose(dateRange: SettlementDateRange): List<QueryPlanDiagnostic> {
		val settlementDate = dateRange.startInclusive.atZone(SEOUL_ZONE).toLocalDate()
		val bounds = SettlementRunJdbcRepository(jdbcTemplate).findEntryBounds(settlementDate)
		val minId = checkNotNull(bounds.minId) { "query plan 진단 대상 정산 원천이 없습니다." }
		val maxId = checkNotNull(bounds.maxId) { "query plan 진단 대상 정산 원천이 없습니다." }
		return listOf(
			diagnose("entryBounds", SettlementRunJdbcRepository.ENTRY_BOUNDS_SQL, settlementDate),
			diagnose("entryAggregate", SettlementRunJdbcRepository.AGGREGATE_ENTRIES_SQL, settlementDate, minId, maxId),
		)
	}

	private fun diagnose(label: String, sql: String, vararg parameters: Any): QueryPlanDiagnostic {
		val startedAt = System.nanoTime()
		val plan = jdbcTemplate.query(
			"EXPLAIN (ANALYZE, BUFFERS, WAL) $sql",
			{ resultSet, _ -> resultSet.getString(1) },
			*parameters,
		).joinToString("\n")
		val executionMillis = (System.nanoTime() - startedAt) / 1_000_000
		val returnedRowCount = jdbcTemplate.query(sql, { _, _ -> Unit }, *parameters).size
		return QueryPlanDiagnostic(label, executionMillis, plan, returnedRowCount)
	}

	private companion object {
		val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
	}
}
