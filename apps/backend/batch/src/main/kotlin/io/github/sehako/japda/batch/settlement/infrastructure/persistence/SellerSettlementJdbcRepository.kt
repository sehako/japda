package io.github.sehako.japda.batch.settlement.infrastructure.persistence

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import io.github.sehako.japda.batch.settlement.domain.model.SettlementEntryBounds
import java.time.ZoneOffset
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SellerSettlementJdbcRepository(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun findByIdForUpdate(sellerSettlementId: Long): SellerSettlementSnapshot? =
		jdbcTemplate.query(
			"""
			SELECT id, settlement_run_id, seller_id, recipient_user_id, net_amount, status, credited_at
			FROM seller_settlements
			WHERE id = ?
			FOR UPDATE
			""".trimIndent(),
			{ resultSet, _ ->
				SellerSettlementSnapshot(
					id = resultSet.getLong("id"),
					settlementRunId = resultSet.getLong("settlement_run_id"),
					sellerId = resultSet.getLong("seller_id"),
					recipientUserId = resultSet.getLong("recipient_user_id"),
					netAmount = resultSet.getLong("net_amount"),
					status = SellerSettlementStatus.valueOf(resultSet.getString("status")),
					creditedAt = resultSet.getTimestamp("credited_at")?.toInstant(),
				)
			},
			sellerSettlementId,
		).singleOrNull()

	fun markCredited(sellerSettlementId: Long, creditedAt: Instant) {
		val updated = jdbcTemplate.update(
			"""
			UPDATE seller_settlements
			SET status = 'CREDITED', credited_at = ?
			WHERE id = ? AND status = 'CONFIRMED'
			""".trimIndent(),
			creditedAt.atOffset(ZoneOffset.UTC),
			sellerSettlementId,
		)
		if (updated != 1) {
			throw IllegalStateException("판매자별 정산을 입금 완료 상태로 전환할 수 없습니다: sellerSettlementId=$sellerSettlementId")
		}
	}

	fun findCreditResultMismatchSellerId(settlementRunId: Long): Long? =
		jdbcTemplate.query(
			"""
			SELECT ss.seller_id
			FROM seller_settlements ss
			LEFT JOIN ledger_entries le
			  ON le.source_type = 'SELLER_SETTLEMENT' AND le.source_id = ss.id
			LEFT JOIN wallets w ON w.id = le.wallet_id
			WHERE ss.settlement_run_id = ?
			  AND (
				ss.status <> 'CREDITED'
				OR ss.credited_at IS NULL
				OR (ss.net_amount > 0 AND (
					le.id IS NULL OR w.user_id <> ss.recipient_user_id
					OR le.direction <> 'CREDIT' OR le.amount <> ss.net_amount
				))
				OR (ss.net_amount = 0 AND le.id IS NOT NULL)
			  )
			ORDER BY ss.seller_id
			LIMIT 1
			""".trimIndent(),
			{ resultSet, _ -> resultSet.getLong("seller_id") },
			settlementRunId,
		).singleOrNull()
	fun createTemporarySellerAggregates(settlementDate: LocalDate, entryBounds: SettlementEntryBounds) {
		jdbcTemplate.execute(
			"""
			CREATE TEMPORARY TABLE settlement_seller_aggregates (
				seller_id BIGINT PRIMARY KEY,
				min_recipient_user_id BIGINT NOT NULL,
				max_recipient_user_id BIGINT NOT NULL,
				detail_count BIGINT NOT NULL,
				gross_amount NUMERIC NOT NULL
			) ON COMMIT DROP
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"""
			INSERT INTO settlement_seller_aggregates (
				seller_id, min_recipient_user_id, max_recipient_user_id, detail_count, gross_amount
			)
			SELECT seller_id,
			       MIN(recipient_user_id),
			       MAX(recipient_user_id),
			       COUNT(*)::BIGINT,
			       SUM(gross_amount)::NUMERIC
			FROM settlement_entries
			WHERE settlement_date = ?
			  AND id >= COALESCE(?, id)
			  AND id <= COALESCE(?, id)
			GROUP BY seller_id
			""".trimIndent(),
			settlementDate,
			entryBounds.minId,
			entryBounds.maxId,
		)
	}

	fun aggregateTemporarySellerAggregates(): SettlementAggregate =
		jdbcTemplate.queryForObject(
			"""
			SELECT COALESCE(SUM(detail_count), 0) AS item_count,
			       COALESCE(SUM(gross_amount), 0) AS total_amount
			FROM settlement_seller_aggregates
			""".trimIndent(),
			{ resultSet, _ ->
				SettlementAggregate(
					itemCount = resultSet.getBigDecimal("item_count"),
					totalAmount = resultSet.getBigDecimal("total_amount"),
				)
			},
		)

	fun findTemporaryRecipientMismatchSellerId(): Long? =
		jdbcTemplate.query(
			"""
			SELECT seller_id
			FROM settlement_seller_aggregates
			WHERE min_recipient_user_id <> max_recipient_user_id
			ORDER BY seller_id
			LIMIT 1
			""".trimIndent(),
			{ resultSet, _ -> resultSet.getLong("seller_id") },
		).singleOrNull()

	fun findTemporaryAmountOutOfRangeSellerId(platformFeeRateBps: Int): Long? =
		jdbcTemplate.query(
			"""
			WITH calculated AS (
				SELECT seller_id, gross_amount,
				       FLOOR(gross_amount * ?::NUMERIC / 10000::NUMERIC) AS platform_fee_amount
				FROM settlement_seller_aggregates
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

	fun insertTemporaryAggregates(
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
			SELECT ?, seller_id, min_recipient_user_id, detail_count, gross_amount::BIGINT,
			       FLOOR(gross_amount * ?::NUMERIC / 10000::NUMERIC)::BIGINT,
			       (gross_amount - FLOOR(gross_amount * ?::NUMERIC / 10000::NUMERIC))::BIGINT,
			       'CONFIRMED',
			       ?,
			       ?
			FROM settlement_seller_aggregates
			""".trimIndent(),
			settlementRunId,
			platformFeeRateBps,
			platformFeeRateBps,
			confirmedAt.atOffset(ZoneOffset.UTC),
			confirmedAt.atOffset(ZoneOffset.UTC),
		)
	}

	fun findTemporarySavedResultMismatchSellerId(settlementRunId: Long, platformFeeRateBps: Int): Long? =
		jdbcTemplate.query(
			"""
			WITH expected AS (
				SELECT seller_id, min_recipient_user_id AS recipient_user_id, detail_count, gross_amount,
				       FLOOR(gross_amount * ?::NUMERIC / 10000::NUMERIC) AS platform_fee_amount
				FROM settlement_seller_aggregates
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
				   OR saved.status NOT IN ('CONFIRMED', 'CREDITED')
				   OR saved.confirmed_at IS NULL
			)
			SELECT seller_id FROM mismatches ORDER BY seller_id LIMIT 1
			""".trimIndent(),
			{ resultSet, _ -> resultSet.getLong("seller_id") },
			platformFeeRateBps,
			settlementRunId,
		).singleOrNull()
}

data class SettlementAggregate(
	val itemCount: BigDecimal,
	val totalAmount: BigDecimal,
)

data class SellerSettlementSnapshot(
	val id: Long,
	val settlementRunId: Long,
	val sellerId: Long,
	val recipientUserId: Long,
	val netAmount: Long,
	val status: SellerSettlementStatus,
	val creditedAt: Instant?,
)

enum class SellerSettlementStatus {
	CONFIRMED,
	CREDITED,
}
