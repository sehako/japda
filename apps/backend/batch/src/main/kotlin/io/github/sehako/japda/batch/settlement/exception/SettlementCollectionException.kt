package io.github.sehako.japda.batch.settlement.exception

class SettlementCollectionException(
	val paymentId: Long,
	val errorType: SettlementCollectionErrorType,
) : RuntimeException("paymentId=$paymentId, errorType=$errorType")
