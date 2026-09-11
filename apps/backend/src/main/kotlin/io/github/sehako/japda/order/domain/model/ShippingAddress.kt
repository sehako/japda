package io.github.sehako.japda.order.domain.model

import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
@ConsistentCopyVisibility
data class ShippingAddress private constructor(
	@field:Column(name = "recipient_name", nullable = false, length = 100)
	val recipientName: String,
	@field:Column(name = "phone_number", nullable = false, length = 30)
	val phoneNumber: String,
	@field:Column(name = "postal_code", nullable = false, length = 20)
	val postalCode: String,
	@field:Column(nullable = false, length = 255)
	val address: String,
	@field:Column(name = "detail_address", nullable = false, length = 255)
	val detailAddress: String,
	@field:Column(name = "delivery_message", length = 500)
	val deliveryMessage: String?,
) {
	companion object {
		fun create(
			recipientName: String?,
			phoneNumber: String?,
			postalCode: String?,
			address: String?,
			detailAddress: String?,
			deliveryMessage: String?,
		): ShippingAddress {
			val normalizedRecipientName = required(recipientName, 100, OrderErrorCode.RECIPIENT_NAME_INVALID)
			val normalizedPhoneNumber = required(phoneNumber, 30, OrderErrorCode.PHONE_NUMBER_INVALID)
			val normalizedPostalCode = required(postalCode, 20, OrderErrorCode.POSTAL_CODE_INVALID)
			val normalizedAddress = required(address, 255, OrderErrorCode.ADDRESS_INVALID)
			val normalizedDetailAddress = required(detailAddress, 255, OrderErrorCode.DETAIL_ADDRESS_INVALID)
			val normalizedDeliveryMessage = deliveryMessage?.trim()?.takeIf(String::isNotEmpty)
			if (normalizedDeliveryMessage != null && normalizedDeliveryMessage.length > 500) {
				throw OrderException(OrderErrorCode.DELIVERY_MESSAGE_INVALID)
			}
			return ShippingAddress(
				normalizedRecipientName,
				normalizedPhoneNumber,
				normalizedPostalCode,
				normalizedAddress,
				normalizedDetailAddress,
				normalizedDeliveryMessage,
			)
		}

		private fun required(value: String?, maxLength: Int, errorCode: OrderErrorCode): String {
			val normalized = value?.trim()
			if (normalized.isNullOrEmpty() || normalized.length > maxLength) throw OrderException(errorCode)
			return normalized
		}
	}
}
