package io.github.sehako.japda.order.presentation

import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.order.application.CreateOrderDto
import io.github.sehako.japda.sale.presentation.StrictNullableIntDeserializer
import io.github.sehako.japda.sale.presentation.StrictNullableLongDeserializer
import java.util.UUID
import tools.jackson.databind.annotation.JsonDeserialize

data class CreateOrderRequest(
	@field:JsonDeserialize(using = StrictNullableLongDeserializer::class)
	val saleId: Long?,
	@field:JsonDeserialize(using = StrictNullableIntDeserializer::class)
	val quantity: Int?,
	val shippingAddress: ShippingAddressRequest?,
) {
	fun toDto(buyerId: Long, idempotencyKey: UUID): CreateOrderDto {
		val shippingAddress = shippingAddress
			?: throw CommonException(CommonErrorCode.REQUEST_BODY_MALFORMED)
		return CreateOrderDto(
			buyerId,
			idempotencyKey,
			saleId,
			quantity,
			shippingAddress.recipientName,
			shippingAddress.phoneNumber,
			shippingAddress.postalCode,
			shippingAddress.address,
			shippingAddress.detailAddress,
			shippingAddress.deliveryMessage,
		)
	}
}

data class ShippingAddressRequest(
	val recipientName: String?,
	val phoneNumber: String?,
	val postalCode: String?,
	val address: String?,
	val detailAddress: String?,
	val deliveryMessage: String?,
)
