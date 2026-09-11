package io.github.sehako.japda.order.application.dto

import io.github.sehako.japda.order.domain.model.OrderRequest
import java.util.UUID

data class CreateOrderDto(
	val buyerId: Long,
	val idempotencyKey: UUID,
	val saleId: Long?,
	val quantity: Int?,
	val recipientName: String?,
	val phoneNumber: String?,
	val postalCode: String?,
	val address: String?,
	val detailAddress: String?,
	val deliveryMessage: String?,
) {
	fun toDomainRequest(): OrderRequest = OrderRequest.create(
		buyerId,
		idempotencyKey,
		saleId,
		quantity,
		recipientName,
		phoneNumber,
		postalCode,
		address,
		detailAddress,
		deliveryMessage,
	)
}
