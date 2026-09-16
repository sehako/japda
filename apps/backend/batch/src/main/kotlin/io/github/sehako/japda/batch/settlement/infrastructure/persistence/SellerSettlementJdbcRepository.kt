package io.github.sehako.japda.batch.settlement.infrastructure.persistence

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Repository

@Repository
class SellerSettlementJdbcRepository(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun lockDetails(settlementRunId: Long) {
		jdbcTemplate.query(
			"SELECT id FROM settlement_details WHERE settlement_run_id = ? FOR UPDATE",
			RowCallbackHandler { },
			settlementRunId,
		)
	}

	fun lockSavedResults(settlementRunId: Long) {
		jdbcTemplate.query(
			"SELECT id FROM seller_settlements WHERE settlement_run_id = ? FOR UPDATE",
			RowCallbackHandler { },
			settlementRunId,
		)
	}

	fun aggregateDetails(settlementRunId: Long): SettlementAggregate =
		jdbcTemplate.queryForObject(
			"""
			SELECT COUNT(*) AS item_count, COALESCE(SUM(gross_amount), 0) AS total_amount
			FROM settlement_details
			WHERE settlement_run_id = ?
			""".trimIndent(),
			{ resultSet, _ ->
				SettlementAggregate(
					itemCount = resultSet.getBigDecimal("item_count"),
					totalAmount = resultSet.getBigDecimal("total_amount"),
				)
			},
			settlementRunId,
		)

	fun findRecipientMismatchSellerId(settlementRunId: Long): Long? =
		jdbcTemplate.query(
			"""
			SELECT seller_id
			FROM settlement_details
			WHERE settlement_run_id = ?
			GROUP BY seller_id
			HAVING COUNT(DISTINCT recipient_user_id) <> 1
			ORDER BY seller_id
			LIMIT 1
			""".trimIndent(),
			{ resultSet, _ -> resultSet.getLong("seller_id") },
			settlementRunId,
		).singleOrNull()

	fun findAmountOutOfRangeSellerId(settlementRunId: Long, platformFeeRateBps: Int): Long? =
		jdbcTemplate.query(
			"""
			WITH calculated AS (
				SELECT seller_id,
				       SUM(gross_amount)::NUMERIC AS gross_amount,
				       FLOOR(SUM(gross_amount)::NUMERIC * ?::NUMERIC / 10000::NUMERIC) AS platform_fee_amount
				FROM settlement_details
				WHERE settlement_run_id = ?
				GROUP BY seller_id
			)
			SELECT seller_id
			FROM calculated
			WHERE gross_amount > ?::NUMERIC
			   OR platform_fee_amount > ?::NUMERIC
			   OR gross_amount - platform_fee_amount > ?::NUMERIC
			ORDER BY seller_id
			LIMIT 1
			""".trimIndent(),
			{ resultSet, _ -> resultSet.getLong("seller_id") },
			platformFeeRateBps,
			settlementRunId,
			Long.MAX_VALUE.toString(),
			Long.MAX_VALUE.toString(),
			Long.MAX_VALUE.toString(),
		).singleOrNull()

	fun countBySettlementRunId(settlementRunId: Long): Long =
		jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM seller_settlements WHERE settlement_run_id = ?",
			Long::class.java,
			settlementRunId,
		)!!

	fun insertAggregated(
		settlementRunId: Long,
		platformFeeRateBps: Int,
		confirmedAt: Instant,
	) {
		jdbcTemplate.update(
			"""
			INSERT INTO seller_settlements (
				settlement_run_id, seller_id, recipient_user_id, detail_count, gross_amount,
				platform_fee_amount, net_amount, status, confirmed_at, created_at
			)
			SELECT settlement_run_id,
			       seller_id,
			       MIN(recipient_user_id),
			       COUNT(*)::BIGINT,
			       SUM(gross_amount)::BIGINT,
			       FLOOR(SUM(gross_amount)::NUMERIC * ?::NUMERIC / 10000::NUMERIC)::BIGINT,
			       (SUM(gross_amount)::NUMERIC -
			        FLOOR(SUM(gross_amount)::NUMERIC * ?::NUMERIC / 10000::NUMERIC))::BIGINT,
			       'CONFIRMED',
			       ?,
			       ?
			FROM settlement_details
			WHERE settlement_run_id = ?
			GROUP BY settlement_run_id, seller_id
			""".trimIndent(),
			platformFeeRateBps,
			platformFeeRateBps,
			confirmedAt.atOffset(ZoneOffset.UTC),
			confirmedAt.atOffset(ZoneOffset.UTC),
			settlementRunId,
		)
	}

	fun aggregateSavedResults(settlementRunId: Long): SettlementAggregate =
		jdbcTemplate.queryForObject(
			"""
			SELECT COALESCE(SUM(detail_count), 0) AS item_count,
			       COALESCE(SUM(gross_amount), 0) AS total_amount
			FROM seller_settlements
			WHERE settlement_run_id = ?
			""".trimIndent(),
			{ resultSet, _ ->
				SettlementAggregate(
					itemCount = resultSet.getBigDecimal("item_count"),
					totalAmount = resultSet.getBigDecimal("total_amount"),
				)
			},
			settlementRunId,
		)

	fun findSavedFormulaMismatchSellerId(settlementRunId: Long, platformFeeRateBps: Int): Long? =
		jdbcTemplate.query(
			"""
			SELECT seller_id
			FROM seller_settlements
			WHERE settlement_run_id = ?
			  AND (
				platform_fee_amount::NUMERIC <> FLOOR(gross_amount::NUMERIC * ?::NUMERIC / 10000::NUMERIC)
				OR gross_amount::NUMERIC <> platform_fee_amount::NUMERIC + net_amount::NUMERIC
				OR status <> 'CONFIRMED'
				OR confirmed_at IS NULL
			  )
			ORDER BY seller_id
			LIMIT 1
			""".trimIndent(),
			{ resultSet, _ -> resultSet.getLong("seller_id") },
			settlementRunId,
			platformFeeRateBps,
		).singleOrNull()

	fun findConfirmedResultMismatchSellerId(settlementRunId: Long, platformFeeRateBps: Int): Long? =
		jdbcTemplate.query(
			"""
			WITH expected AS (
				SELECT seller_id,
				       MIN(recipient_user_id) AS recipient_user_id,
				       COUNT(*)::BIGINT AS detail_count,
				       SUM(gross_amount)::NUMERIC AS gross_amount,
				       FLOOR(SUM(gross_amount)::NUMERIC * ?::NUMERIC / 10000::NUMERIC) AS platform_fee_amount
				FROM settlement_details
				WHERE settlement_run_id = ?
				GROUP BY seller_id
			), saved AS (
				SELECT *
				FROM seller_settlements
				WHERE settlement_run_id = ?
			), mismatches AS (
				SELECT COALESCE(expected.seller_id, saved.seller_id) AS seller_id
				FROM expected
				FULL OUTER JOIN saved ON saved.seller_id = expected.seller_id
				WHERE expected.seller_id IS NULL
				   OR saved.seller_id IS NULL
				   OR saved.recipient_user_id <> expected.recipient_user_id
				   OR saved.detail_count <> expected.detail_count
				   OR saved.gross_amount::NUMERIC <> expected.gross_amount
				   OR saved.platform_fee_amount::NUMERIC <> expected.platform_fee_amount
				   OR saved.net_amount::NUMERIC <> expected.gross_amount - expected.platform_fee_amount
				   OR saved.status <> 'CONFIRMED'
				   OR saved.confirmed_at IS NULL
			)
			SELECT seller_id FROM mismatches ORDER BY seller_id LIMIT 1
			""".trimIndent(),
			{ resultSet, _ -> resultSet.getLong("seller_id") },
			platformFeeRateBps,
			settlementRunId,
			settlementRunId,
		).singleOrNull()
}

data class SettlementAggregate(
	val itemCount: BigDecimal,
	val totalAmount: BigDecimal,
)
