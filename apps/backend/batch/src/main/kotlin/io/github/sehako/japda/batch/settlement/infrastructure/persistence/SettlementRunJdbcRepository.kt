package io.github.sehako.japda.batch.settlement.infrastructure.persistence

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SettlementRunJdbcRepository(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun findBySettlementDate(settlementDate: LocalDate): SettlementRunSnapshot? =
		jdbcTemplate.query(
			"""
			SELECT id, settlement_date, platform_fee_rate_bps, status,
			       collected_count, collected_amount, collection_completed_at
			FROM settlement_runs
			WHERE settlement_date = ?
			""".trimIndent(),
			{ resultSet, _ ->
				SettlementRunSnapshot(
					id = resultSet.getLong("id"),
					settlementDate = resultSet.getObject("settlement_date", LocalDate::class.java),
					platformFeeRateBps = resultSet.getInt("platform_fee_rate_bps"),
					status = SettlementRunStatus.valueOf(resultSet.getString("status")),
					collectedCount = resultSet.getLong("collected_count"),
					collectedAmount = resultSet.getLong("collected_amount"),
					collectionCompletedAt = resultSet.getTimestamp("collection_completed_at")?.toInstant(),
				)
			},
			settlementDate,
		).singleOrNull()

	fun create(settlementDate: LocalDate, platformFeeRateBps: Int, startedAt: Instant): SettlementRunSnapshot {
		val id = jdbcTemplate.queryForObject(
			"""
			INSERT INTO settlement_runs (
				settlement_date, platform_fee_rate_bps, status, collected_count, collected_amount, started_at, created_at
			) VALUES (?, ?, 'COLLECTING', 0, 0, ?, ?)
			RETURNING id
			""".trimIndent(),
			Long::class.java,
			settlementDate,
			platformFeeRateBps,
			startedAt.atOffset(ZoneOffset.UTC),
			startedAt.atOffset(ZoneOffset.UTC),
		) ?: error("SettlementRun 생성 결과에 id가 없습니다.")
		return SettlementRunSnapshot(
			id = id,
			settlementDate = settlementDate,
			platformFeeRateBps = platformFeeRateBps,
			status = SettlementRunStatus.COLLECTING,
			collectedCount = 0,
			collectedAmount = 0,
			collectionCompletedAt = null,
		)
	}

	fun aggregateDetails(settlementRunId: Long): SettlementDetailAggregate =
		jdbcTemplate.queryForObject(
			"""
			SELECT COUNT(*) AS collected_count, COALESCE(SUM(gross_amount), 0) AS collected_amount
			FROM settlement_details
			WHERE settlement_run_id = ?
			""".trimIndent(),
			{ resultSet, _ ->
				SettlementDetailAggregate(
					collectedCount = resultSet.getLong("collected_count"),
					collectedAmount = resultSet.getLong("collected_amount"),
				)
			},
			settlementRunId,
		)

	fun markCollected(settlementRunId: Long, aggregate: SettlementDetailAggregate, completedAt: Instant) {
		val updated = jdbcTemplate.update(
			"""
			UPDATE settlement_runs
			SET status = 'COLLECTED', collected_count = ?, collected_amount = ?, collection_completed_at = ?
			WHERE id = ? AND status = 'COLLECTING'
			""".trimIndent(),
			aggregate.collectedCount,
			aggregate.collectedAmount,
			completedAt.atOffset(ZoneOffset.UTC),
			settlementRunId,
		)
		if (updated != 1) {
			throw SettlementRunStateException("COLLECTING 상태의 SettlementRun을 완료할 수 없습니다: settlementRunId=$settlementRunId")
		}
	}

	fun findById(settlementRunId: Long): SettlementRunSnapshot? =
		jdbcTemplate.query(
			"""
			SELECT id, settlement_date, platform_fee_rate_bps, status,
			       collected_count, collected_amount, collection_completed_at
			FROM settlement_runs
			WHERE id = ?
			""".trimIndent(),
			{ resultSet, _ ->
				SettlementRunSnapshot(
					id = resultSet.getLong("id"),
					settlementDate = resultSet.getObject("settlement_date", LocalDate::class.java),
					platformFeeRateBps = resultSet.getInt("platform_fee_rate_bps"),
					status = SettlementRunStatus.valueOf(resultSet.getString("status")),
					collectedCount = resultSet.getLong("collected_count"),
					collectedAmount = resultSet.getLong("collected_amount"),
					collectionCompletedAt = resultSet.getTimestamp("collection_completed_at")?.toInstant(),
				)
			},
			settlementRunId,
		).singleOrNull()
}

data class SettlementRunSnapshot(
	val id: Long,
	val settlementDate: LocalDate,
	val platformFeeRateBps: Int,
	val status: SettlementRunStatus,
	val collectedCount: Long,
	val collectedAmount: Long,
	val collectionCompletedAt: Instant?,
)

data class SettlementDetailAggregate(
	val collectedCount: Long,
	val collectedAmount: Long,
)

enum class SettlementRunStatus {
	COLLECTING,
	COLLECTED,
}

class SettlementRunStateException(message: String) : IllegalStateException(message)
