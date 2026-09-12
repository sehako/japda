package io.github.sehako.japda.shippingaddress.presentation.request

import io.github.sehako.japda.shippingaddress.application.dto.CreateBuyerShippingAddressDto

data class CreateBuyerShippingAddressRequest(
	val addressName: String?,
	val recipientName: String?,
	val phoneNumber: String?,
	val postalCode: String?,
	val address: String?,
	val detailAddress: String?,
	val deliveryMessage: String?,
) {
	fun toDto(buyerId: Long): CreateBuyerShippingAddressDto = CreateBuyerShippingAddressDto(
		buyerId,
		addressName,
		recipientName,
		phoneNumber,
		postalCode,
		address,
		detailAddress,
		deliveryMessage,
	)
}
