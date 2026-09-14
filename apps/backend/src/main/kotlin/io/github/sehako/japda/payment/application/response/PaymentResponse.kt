package io.github.sehako.japda.payment.application.response

import java.time.Instant

data class PaymentResponse(
	val orderId: Long,
	val paymentOrderId: String,
	val status: String,
	val totalAmount: Long,
	val approvedAt: Instant,
)
