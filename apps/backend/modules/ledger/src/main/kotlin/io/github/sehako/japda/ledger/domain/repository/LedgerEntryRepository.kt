package io.github.sehako.japda.ledger.domain.repository

import io.github.sehako.japda.ledger.domain.model.LedgerDirection
import io.github.sehako.japda.ledger.domain.model.LedgerEntrySnapshot
import io.github.sehako.japda.ledger.domain.model.LedgerSourceType
import java.time.Instant

interface LedgerEntryRepository {
	fun findBySource(sourceType: LedgerSourceType, sourceId: Long): LedgerEntrySnapshot?

	fun create(
		walletId: Long,
		direction: LedgerDirection,
		amount: Long,
		balanceAfter: Long,
		sourceType: LedgerSourceType,
		sourceId: Long,
		now: Instant,
	)
}
