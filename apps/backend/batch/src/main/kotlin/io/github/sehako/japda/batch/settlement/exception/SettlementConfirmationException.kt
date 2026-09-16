package io.github.sehako.japda.batch.settlement.exception

class SettlementConfirmationException(
	val errorType: SettlementConfirmationErrorType,
	message: String,
	val sellerId: Long? = null,
	cause: Throwable? = null,
) : IllegalStateException(message, cause)
