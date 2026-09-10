package io.github.sehako.japda.global.exception

enum class CommonErrorCode(
	override val code: String,
	override val message: String,
	override val category: ErrorCategory,
) : ErrorCode {
	REQUEST_HEADER_MISSING(
		"COMMON_REQUEST_HEADER_MISSING",
		"필수 요청 헤더가 없습니다.",
		ErrorCategory.INVALID_REQUEST,
	),
	REQUEST_HEADER_INVALID(
		"COMMON_REQUEST_HEADER_INVALID",
		"요청 헤더 형식이 올바르지 않습니다.",
		ErrorCategory.INVALID_REQUEST,
	),
	REQUEST_BODY_MALFORMED(
		"COMMON_REQUEST_BODY_MALFORMED",
		"요청 본문 형식이 올바르지 않습니다.",
		ErrorCategory.INVALID_REQUEST,
	),
	INTERNAL_SERVER_ERROR(
		"COMMON_INTERNAL_SERVER_ERROR",
		"서버 내부 오류가 발생했습니다.",
		ErrorCategory.INTERNAL_SERVER_ERROR,
	),
	;

	override val property: String? = null
}
