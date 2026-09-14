package io.github.sehako.japda.payment.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class PaymentErrorCode(
	override val code: String,
	override val message: String,
	override val property: String?,
	override val category: ErrorCategory,
) : ErrorCode {
	KEY_INVALID("PAYMENT_KEY_INVALID", "결제 키는 1~200자여야 합니다.", "paymentKey", ErrorCategory.INVALID_REQUEST),
	ORDER_ID_INVALID("PAYMENT_ORDER_ID_INVALID", "결제 주문 식별자 형식이 올바르지 않습니다.", "orderId", ErrorCategory.INVALID_REQUEST),
	AMOUNT_INVALID("PAYMENT_AMOUNT_INVALID", "결제 금액은 양의 정수여야 합니다.", "amount", ErrorCategory.INVALID_REQUEST),
	ORDER_NOT_FOUND("PAYMENT_ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.", null, ErrorCategory.NOT_FOUND),
	AMOUNT_MISMATCH("PAYMENT_AMOUNT_MISMATCH", "주문 금액과 요청 금액이 다릅니다.", null, ErrorCategory.CONFLICT),
	ORDER_EXPIRED("PAYMENT_ORDER_EXPIRED", "결제 가능한 주문 시간이 지났습니다.", null, ErrorCategory.CONFLICT),
	KEY_CONFLICT("PAYMENT_KEY_CONFLICT", "다른 결제 키를 사용할 수 없습니다.", null, ErrorCategory.CONFLICT),
	IN_PROGRESS("PAYMENT_CONFIRMATION_IN_PROGRESS", "결제 승인이 처리 중입니다.", null, ErrorCategory.CONFLICT),
	FAILED("PAYMENT_CONFIRMATION_FAILED", "결제 승인이 실패했습니다.", null, ErrorCategory.CONFLICT),
	UNAVAILABLE("PAYMENT_CONFIRMATION_UNAVAILABLE", "결제 결과를 확인할 수 없습니다.", null, ErrorCategory.SERVICE_UNAVAILABLE),
	REVIEW_REQUIRED("PAYMENT_REVIEW_REQUIRED", "결제에 수동 확인이 필요합니다.", null, ErrorCategory.CONFLICT),
}
