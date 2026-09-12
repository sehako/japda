package io.github.sehako.japda.shippingaddress.application.response

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddress
import java.time.Instant

data class BuyerShippingAddressResponse(
	val shippingAddressId: Long,
	val addressName: String,
	val recipientName: String,
	val phoneNumber: String,
	val postalCode: String,
	val address: String,
	val detailAddress: String,
	val deliveryMessage: String?,
	val createdAt: Instant,
)

fun BuyerShippingAddress.toResponse(): BuyerShippingAddressResponse = BuyerShippingAddressResponse(
	shippingAddressId = requireNotNull(id),
	addressName = addressName,
	recipientName = recipientName,
	phoneNumber = phoneNumber,
	postalCode = postalCode,
	address = address,
	detailAddress = detailAddress,
	deliveryMessage = deliveryMessage,
	createdAt = createdAt,
)
