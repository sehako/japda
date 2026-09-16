package io.github.sehako.japda.ledger.domain.model

enum class LedgerDirection {
	CREDIT,
	DEBIT,
}

enum class LedgerSourceType {
	SELLER_SETTLEMENT,
}

data class WalletSnapshot(
	val id: Long,
	val userId: Long,
	val balance: Long,
)

data class LedgerEntrySnapshot(
	val id: Long,
	val walletId: Long,
	val walletUserId: Long,
	val direction: LedgerDirection,
	val amount: Long,
	val balanceAfter: Long,
	val sourceType: LedgerSourceType,
	val sourceId: Long,
)
