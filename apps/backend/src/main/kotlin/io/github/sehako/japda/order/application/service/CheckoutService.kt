package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.response.CheckoutResponse
import io.github.sehako.japda.order.application.response.CheckoutShippingAddressResponse
import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshotQuery
import io.github.sehako.japda.order.domain.repository.CheckoutShippingAddressQuery
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CheckoutService(
	private val productSnapshotQuery: CheckoutProductSnapshotQuery,
	private val shippingAddressQuery: CheckoutShippingAddressQuery,
) {
	@Transactional(readOnly = true)
	fun find(buyerId: Long, saleId: Long, quantity: Int): CheckoutResponse {
		if (saleId <= 0) throw OrderException(OrderErrorCode.SALE_ID_INVALID)
		if (quantity <= 0) throw OrderException(OrderErrorCode.QUANTITY_INVALID)
		val snapshot = productSnapshotQuery.findBySaleId(saleId) ?: throw OrderException(OrderErrorCode.SALE_NOT_FOUND)
		val productName = checkNotNull(snapshot.productName) { "판매 일정의 상품이 없습니다." }
		val imageKey = checkNotNull(snapshot.representativeImageObjectKey) { "대표 상품 이미지가 없습니다." }
		val totalPrice = try {
			Math.multiplyExact(snapshot.unitPrice, quantity.toLong())
		} catch (_: ArithmeticException) {
			throw OrderException(OrderErrorCode.TOTAL_PRICE_INVALID)
		}
		return CheckoutResponse(
			saleId = snapshot.saleId,
			productName = productName,
			representativeImagePath = "/$imageKey",
			quantity = quantity,
			unitPrice = snapshot.unitPrice,
			totalPrice = totalPrice,
			shippingAddresses = shippingAddressQuery.findByBuyerId(buyerId).mapNotNull { address ->
				address.shippingAddressId?.let { id ->
					CheckoutShippingAddressResponse(
						shippingAddressId = id,
						addressName = checkNotNull(address.addressName),
						recipientName = checkNotNull(address.recipientName),
						phoneNumber = checkNotNull(address.phoneNumber),
						postalCode = checkNotNull(address.postalCode),
						address = checkNotNull(address.address),
						detailAddress = checkNotNull(address.detailAddress),
						deliveryMessage = address.deliveryMessage,
					)
				}
			},
		)
	}
}
