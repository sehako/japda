package io.github.sehako.japda.batch.settlement.exception

class SettlementCreditException(
	val errorType: SettlementCreditErrorType,
	message: String,
	val sellerSettlementId: Long? = null,
	val sellerId: Long? = null,
	cause: Throwable? = null,
) : IllegalStateException(message, cause)

enum class SettlementCreditErrorType {
	RUN_NOT_FOUND,
	SELLER_SETTLEMENT_NOT_FOUND,
	RUN_MISMATCH,
	INVALID_RUN_STATUS,
	INVALID_SELLER_SETTLEMENT_STATUS,
	UNEXPECTED_LEDGER_ENTRY,
	CREDIT_RESULT_MISMATCH,
	STATE_TRANSITION_FAILED,
}
