package io.github.sehako.japda.payment.presentation.request

import io.github.sehako.japda.payment.application.dto.ConfirmPaymentDto
import io.github.sehako.japda.sale.presentation.serializer.StrictNullableLongDeserializer
import tools.jackson.databind.annotation.JsonDeserialize

data class ConfirmPaymentRequest(
	val paymentKey: String?,
	val orderId: String?,
	@field:JsonDeserialize(using = StrictNullableLongDeserializer::class)
	val amount: Long?,
) {
	fun toDto(buyerId: Long) = ConfirmPaymentDto(buyerId, paymentKey, orderId, amount)
}
