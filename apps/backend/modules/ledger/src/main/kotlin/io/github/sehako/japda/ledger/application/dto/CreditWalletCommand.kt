package io.github.sehako.japda.ledger.application.dto

import io.github.sehako.japda.ledger.domain.model.LedgerSourceType

data class CreditWalletCommand(
	val userId: Long,
	val amount: Long,
	val sourceType: LedgerSourceType,
	val sourceId: Long,
)

data class CreditWalletResult(
	val walletId: Long,
	val balanceAfter: Long,
	val alreadyApplied: Boolean,
)
