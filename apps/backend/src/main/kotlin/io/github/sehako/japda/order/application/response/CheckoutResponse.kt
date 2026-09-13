package io.github.sehako.japda.order.application.response

data class CheckoutResponse(
	val saleId: Long,
	val productName: String,
	val representativeImagePath: String,
	val quantity: Int,
	val unitPrice: Long,
	val totalPrice: Long,
	val shippingAddresses: List<CheckoutShippingAddressResponse>,
)

data class CheckoutShippingAddressResponse(
	val shippingAddressId: Long,
	val addressName: String,
	val recipientName: String,
	val phoneNumber: String,
	val postalCode: String,
	val address: String,
	val detailAddress: String,
	val deliveryMessage: String?,
)
