package io.github.sehako.japda.payment.application.client

import java.time.Instant

interface TossPaymentClient {
	fun confirm(paymentKey: String, orderId: String, amount: Long, idempotencyKey: String): TossPaymentResult

	fun lookup(paymentKey: String): TossPaymentResult
}

object TossPaymentStatuses {
	const val DONE = "DONE"
	const val IN_PROGRESS = "IN_PROGRESS"
	const val ABORTED = "ABORTED"
	const val EXPIRED = "EXPIRED"
}

sealed interface TossPaymentResult {
	data class Record(
		val paymentKey: String,
		val orderId: String,
		val totalAmount: Long,
		val status: String,
		val approvedAt: Instant?,
	) : TossPaymentResult

	data object ConfirmedFailure : TossPaymentResult

	data object NotFound : TossPaymentResult

	data object InvalidData : TossPaymentResult

	data object Unavailable : TossPaymentResult
}
