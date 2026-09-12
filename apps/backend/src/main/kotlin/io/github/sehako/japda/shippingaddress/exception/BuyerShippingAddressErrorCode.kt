package io.github.sehako.japda.shippingaddress.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class BuyerShippingAddressErrorCode(
	override val code: String,
	override val message: String,
	override val property: String?,
	override val category: ErrorCategory = ErrorCategory.INVALID_REQUEST,
) : ErrorCode {
	NAME_INVALID("BUYER_SHIPPING_ADDRESS_NAME_INVALID", "배송지명은 1~100자여야 합니다.", "addressName"),
	RECIPIENT_NAME_INVALID("BUYER_SHIPPING_ADDRESS_RECIPIENT_NAME_INVALID", "수령인 이름은 1~100자여야 합니다.", "recipientName"),
	PHONE_NUMBER_INVALID("BUYER_SHIPPING_ADDRESS_PHONE_NUMBER_INVALID", "전화번호는 1~30자여야 합니다.", "phoneNumber"),
	POSTAL_CODE_INVALID("BUYER_SHIPPING_ADDRESS_POSTAL_CODE_INVALID", "우편번호는 1~20자여야 합니다.", "postalCode"),
	ADDRESS_INVALID("BUYER_SHIPPING_ADDRESS_ADDRESS_INVALID", "주소는 1~255자여야 합니다.", "address"),
	DETAIL_ADDRESS_INVALID("BUYER_SHIPPING_ADDRESS_DETAIL_ADDRESS_INVALID", "상세 주소는 1~255자여야 합니다.", "detailAddress"),
	DELIVERY_MESSAGE_INVALID("BUYER_SHIPPING_ADDRESS_DELIVERY_MESSAGE_INVALID", "배송 메시지는 500자 이하여야 합니다.", "deliveryMessage"),
	LIMIT_EXCEEDED("BUYER_SHIPPING_ADDRESS_LIMIT_EXCEEDED", "배송지는 최대 10개까지 등록할 수 있습니다.", null, ErrorCategory.CONFLICT),
	NAME_DUPLICATED("BUYER_SHIPPING_ADDRESS_NAME_DUPLICATED", "이미 등록된 배송지명입니다.", "addressName", ErrorCategory.CONFLICT),
}
