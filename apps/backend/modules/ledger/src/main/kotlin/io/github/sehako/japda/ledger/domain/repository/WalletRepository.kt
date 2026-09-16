package io.github.sehako.japda.ledger.domain.repository

import io.github.sehako.japda.ledger.domain.model.WalletSnapshot
import java.time.Instant

interface WalletRepository {
	fun createIfAbsent(userId: Long, now: Instant)

	fun findByUserIdForUpdate(userId: Long): WalletSnapshot?

	fun updateBalance(walletId: Long, balance: Long, now: Instant)
}
