package io.github.sehako.japda.ledger.infrastructure.persistence

import io.github.sehako.japda.ledger.domain.model.LedgerDirection
import io.github.sehako.japda.ledger.domain.model.LedgerEntrySnapshot
import io.github.sehako.japda.ledger.domain.model.LedgerSourceType
import io.github.sehako.japda.ledger.domain.model.WalletSnapshot
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import io.github.sehako.japda.ledger.domain.repository.WalletRepository
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.jdbc.core.JdbcTemplate

class JdbcWalletRepository(private val jdbcTemplate: JdbcTemplate) : WalletRepository {
	override fun createIfAbsent(userId: Long, now: Instant) {
		jdbcTemplate.update(
			"""
			INSERT INTO wallets (user_id, balance, created_at, updated_at)
			VALUES (?, 0, ?, ?)
			ON CONFLICT (user_id) DO NOTHING
			""".trimIndent(),
			userId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
		)
	}

	override fun findByUserIdForUpdate(userId: Long): WalletSnapshot? = jdbcTemplate.query(
		"SELECT id, user_id, balance FROM wallets WHERE user_id = ? FOR UPDATE",
		{ rs, _ -> WalletSnapshot(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("balance")) },
		userId,
	).singleOrNull()

	override fun updateBalance(walletId: Long, balance: Long, now: Instant) {
		val updated = jdbcTemplate.update(
			"UPDATE wallets SET balance = ?, updated_at = ? WHERE id = ?",
			balance, now.atOffset(ZoneOffset.UTC), walletId,
		)
		check(updated == 1) { "지갑 잔액 변경 대상이 정확히 하나가 아닙니다. walletId=$walletId" }
	}
}

class JdbcLedgerEntryRepository(private val jdbcTemplate: JdbcTemplate) : LedgerEntryRepository {
	override fun findBySource(sourceType: LedgerSourceType, sourceId: Long): LedgerEntrySnapshot? = jdbcTemplate.query(
		"""
		SELECT le.id, le.wallet_id, w.user_id AS wallet_user_id, le.direction, le.amount,
		       le.balance_after, le.source_type, le.source_id
		FROM ledger_entries le
		JOIN wallets w ON w.id = le.wallet_id
		WHERE le.source_type = ? AND le.source_id = ?
		""".trimIndent(),
		{ rs, _ ->
			LedgerEntrySnapshot(
				rs.getLong("id"),
				rs.getLong("wallet_id"),
				rs.getLong("wallet_user_id"),
				LedgerDirection.valueOf(rs.getString("direction")),
				rs.getLong("amount"),
				rs.getLong("balance_after"),
				LedgerSourceType.valueOf(rs.getString("source_type")),
				rs.getLong("source_id"),
			)
		},
		sourceType.name, sourceId,
	).singleOrNull()

	override fun create(
		walletId: Long,
		direction: LedgerDirection,
		amount: Long,
		balanceAfter: Long,
		sourceType: LedgerSourceType,
		sourceId: Long,
		now: Instant,
	) {
		val created = jdbcTemplate.update(
			"""
			INSERT INTO ledger_entries (
				wallet_id, direction, amount, balance_after, source_type, source_id, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			walletId, direction.name, amount, balanceAfter, sourceType.name, sourceId, now.atOffset(ZoneOffset.UTC),
		)
		check(created == 1) { "원장 생성 결과가 정확히 하나가 아닙니다. sourceType=$sourceType, sourceId=$sourceId" }
	}
}
