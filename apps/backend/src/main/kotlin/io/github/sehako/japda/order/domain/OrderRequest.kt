package io.github.sehako.japda.order.domain

import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import java.util.UUID

@ConsistentCopyVisibility
data class OrderRequest private constructor(
	val buyerId: Long,
	val idempotencyKey: UUID,
	val saleId: Long,
	val quantity: Int,
	val shippingAddress: ShippingAddress,
) {
	companion object {
		fun create(
			buyerId: Long,
			idempotencyKey: UUID,
			saleId: Long?,
			quantity: Int?,
			recipientName: String?,
			phoneNumber: String?,
			postalCode: String?,
			address: String?,
			detailAddress: String?,
			deliveryMessage: String?,
		): OrderRequest {
			if (buyerId <= 0) throw IllegalArgumentException("구매자 식별자는 양수여야 합니다.")
			if (saleId == null || saleId <= 0) throw OrderException(OrderErrorCode.SALE_ID_INVALID)
			if (quantity == null || quantity <= 0) throw OrderException(OrderErrorCode.QUANTITY_INVALID)
			return OrderRequest(
				buyerId,
				idempotencyKey,
				saleId,
				quantity,
				ShippingAddress.create(
					recipientName,
					phoneNumber,
					postalCode,
					address,
					detailAddress,
					deliveryMessage,
				),
			)
		}
	}
}
