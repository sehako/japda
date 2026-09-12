package io.github.sehako.japda.shippingaddress.domain.model

import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressErrorCode
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressException

@ConsistentCopyVisibility
data class BuyerShippingAddressDetails private constructor(
	val addressName: String,
	val recipientName: String,
	val phoneNumber: String,
	val postalCode: String,
	val address: String,
	val detailAddress: String,
	val deliveryMessage: String?,
) {
	companion object {
		fun create(
			addressName: String?,
			recipientName: String?,
			phoneNumber: String?,
			postalCode: String?,
			address: String?,
			detailAddress: String?,
			deliveryMessage: String?,
		): BuyerShippingAddressDetails {
			val normalizedMessage = deliveryMessage?.trim()?.takeIf(String::isNotEmpty)
			if (normalizedMessage != null && normalizedMessage.length > DELIVERY_MESSAGE_MAX_LENGTH) {
				throw BuyerShippingAddressException(BuyerShippingAddressErrorCode.DELIVERY_MESSAGE_INVALID)
			}
			return BuyerShippingAddressDetails(
				required(addressName, ADDRESS_NAME_MAX_LENGTH, BuyerShippingAddressErrorCode.NAME_INVALID),
				required(recipientName, RECIPIENT_NAME_MAX_LENGTH, BuyerShippingAddressErrorCode.RECIPIENT_NAME_INVALID),
				required(phoneNumber, PHONE_NUMBER_MAX_LENGTH, BuyerShippingAddressErrorCode.PHONE_NUMBER_INVALID),
				required(postalCode, POSTAL_CODE_MAX_LENGTH, BuyerShippingAddressErrorCode.POSTAL_CODE_INVALID),
				required(address, ADDRESS_MAX_LENGTH, BuyerShippingAddressErrorCode.ADDRESS_INVALID),
				required(detailAddress, DETAIL_ADDRESS_MAX_LENGTH, BuyerShippingAddressErrorCode.DETAIL_ADDRESS_INVALID),
				normalizedMessage,
			)
		}

		private fun required(value: String?, maxLength: Int, errorCode: BuyerShippingAddressErrorCode): String {
			val normalized = value?.trim()
			if (normalized.isNullOrEmpty() || normalized.length > maxLength) {
				throw BuyerShippingAddressException(errorCode)
			}
			return normalized
		}

		private const val ADDRESS_NAME_MAX_LENGTH = 100
		private const val RECIPIENT_NAME_MAX_LENGTH = 100
		private const val PHONE_NUMBER_MAX_LENGTH = 30
		private const val POSTAL_CODE_MAX_LENGTH = 20
		private const val ADDRESS_MAX_LENGTH = 255
		private const val DETAIL_ADDRESS_MAX_LENGTH = 255
		private const val DELIVERY_MESSAGE_MAX_LENGTH = 500
	}
}
