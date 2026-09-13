package io.github.sehako.japda.order.domain.repository

interface CheckoutQueryRepository {
	fun findBySaleIdAndBuyerId(saleId: Long, buyerId: Long): List<CheckoutQueryRow>
}

data class CheckoutQueryRow(
	val saleId: Long,
	val productName: String?,
	val representativeImageObjectKey: String?,
	val unitPrice: Long,
	val shippingAddressId: Long?,
	val addressName: String?,
	val recipientName: String?,
	val phoneNumber: String?,
	val postalCode: String?,
	val address: String?,
	val detailAddress: String?,
	val deliveryMessage: String?,
)
