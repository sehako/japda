package io.github.sehako.japda.shippingaddress.application.dto

data class CreateBuyerShippingAddressDto(
	val buyerId: Long,
	val addressName: String?,
	val recipientName: String?,
	val phoneNumber: String?,
	val postalCode: String?,
	val address: String?,
	val detailAddress: String?,
	val deliveryMessage: String?,
)
