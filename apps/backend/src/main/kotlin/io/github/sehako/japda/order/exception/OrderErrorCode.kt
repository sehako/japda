package io.github.sehako.japda.order.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class OrderErrorCode(
	override val code: String,
	override val message: String,
	override val property: String?,
	override val category: ErrorCategory = ErrorCategory.INVALID_REQUEST,
) : ErrorCode {
	SALE_ID_INVALID("ORDER_SALE_ID_INVALID", "판매 일정 식별자는 양수여야 합니다.", "saleId"),
	QUANTITY_INVALID("ORDER_QUANTITY_INVALID", "주문 수량은 양수여야 합니다.", "quantity"),
	RECIPIENT_NAME_INVALID("ORDER_RECIPIENT_NAME_INVALID", "수령인 이름은 1~100자여야 합니다.", "recipientName"),
	PHONE_NUMBER_INVALID("ORDER_PHONE_NUMBER_INVALID", "전화번호는 1~30자여야 합니다.", "phoneNumber"),
	POSTAL_CODE_INVALID("ORDER_POSTAL_CODE_INVALID", "우편번호는 1~20자여야 합니다.", "postalCode"),
	ADDRESS_INVALID("ORDER_ADDRESS_INVALID", "주소는 1~255자여야 합니다.", "address"),
	DETAIL_ADDRESS_INVALID("ORDER_DETAIL_ADDRESS_INVALID", "상세 주소는 1~255자여야 합니다.", "detailAddress"),
	DELIVERY_MESSAGE_INVALID("ORDER_DELIVERY_MESSAGE_INVALID", "배송 메시지는 500자 이하여야 합니다.", "deliveryMessage"),
	SALE_NOT_FOUND("ORDER_SALE_NOT_FOUND", "판매 일정을 찾을 수 없습니다.", "saleId", ErrorCategory.NOT_FOUND),
	SALE_NOT_OPEN("ORDER_SALE_NOT_OPEN", "판매 중인 일정이 아닙니다.", "saleId", ErrorCategory.CONFLICT),
	QUANTITY_UNAVAILABLE("ORDER_QUANTITY_UNAVAILABLE", "남은 판매 수량이 부족합니다.", "quantity", ErrorCategory.CONFLICT),
	IDEMPOTENCY_CONFLICT("ORDER_IDEMPOTENCY_CONFLICT", "같은 멱등성 키에 다른 요청을 사용할 수 없습니다.", null, ErrorCategory.CONFLICT),
	TOTAL_PRICE_INVALID("ORDER_TOTAL_PRICE_INVALID", "주문 총액을 계산할 수 없습니다.", null, ErrorCategory.CONFLICT),
}
