package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.response.CheckoutResponse
import io.github.sehako.japda.order.application.response.CheckoutShippingAddressResponse
import io.github.sehako.japda.order.domain.repository.CheckoutQueryRepository
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CheckoutService(
	private val repository: CheckoutQueryRepository,
) {
	@Transactional(readOnly = true)
	fun find(buyerId: Long, saleId: Long, quantity: Int): CheckoutResponse {
		if (saleId <= 0) throw OrderException(OrderErrorCode.SALE_ID_INVALID)
		if (quantity <= 0) throw OrderException(OrderErrorCode.QUANTITY_INVALID)
		val rows = repository.findBySaleIdAndBuyerId(saleId, buyerId)
		val first = rows.firstOrNull() ?: throw OrderException(OrderErrorCode.SALE_NOT_FOUND)
		val productName = checkNotNull(first.productName) { "판매 일정의 상품이 없습니다." }
		val imageKey = checkNotNull(first.representativeImageObjectKey) { "대표 상품 이미지가 없습니다." }
		val totalPrice = try {
			Math.multiplyExact(first.unitPrice, quantity.toLong())
		} catch (_: ArithmeticException) {
			throw OrderException(OrderErrorCode.TOTAL_PRICE_INVALID)
		}
		return CheckoutResponse(
			saleId = first.saleId,
			productName = productName,
			representativeImagePath = "/$imageKey",
			quantity = quantity,
			unitPrice = first.unitPrice,
			totalPrice = totalPrice,
			shippingAddresses = rows.mapNotNull { row ->
				row.shippingAddressId?.let { id ->
					CheckoutShippingAddressResponse(
						shippingAddressId = id,
						addressName = checkNotNull(row.addressName),
						recipientName = checkNotNull(row.recipientName),
						phoneNumber = checkNotNull(row.phoneNumber),
						postalCode = checkNotNull(row.postalCode),
						address = checkNotNull(row.address),
						detailAddress = checkNotNull(row.detailAddress),
						deliveryMessage = row.deliveryMessage,
					)
				}
			},
		)
	}
}
