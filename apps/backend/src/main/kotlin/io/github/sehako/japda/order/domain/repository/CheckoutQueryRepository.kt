package io.github.sehako.japda.order.domain.repository

interface CheckoutProductSnapshotQuery {
	fun findBySaleId(saleId: Long): CheckoutProductSnapshot?
}

interface CheckoutShippingAddressQuery {
	fun findByBuyerId(buyerId: Long): List<CheckoutShippingAddress>
}

data class CheckoutProductSnapshot(
	val saleId: Long,
	val productName: String?,
	val representativeImageObjectKey: String?,
	val unitPrice: Long,
)

data class CheckoutShippingAddress(
	val shippingAddressId: Long?,
	val addressName: String?,
	val recipientName: String?,
	val phoneNumber: String?,
	val postalCode: String?,
	val address: String?,
	val detailAddress: String?,
	val deliveryMessage: String?,
)
