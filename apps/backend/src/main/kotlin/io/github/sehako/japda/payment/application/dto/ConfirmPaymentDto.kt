package io.github.sehako.japda.payment.application.dto

data class ConfirmPaymentDto(
	val buyerId: Long,
	val paymentKey: String?,
	val orderId: String?,
	val amount: Long?,
)
