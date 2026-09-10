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
	REQUEST_PARAMETER_INVALID(
		"COMMON_REQUEST_PARAMETER_INVALID",
		"요청 파라미터 형식이 올바르지 않습니다.",
		ErrorCategory.INVALID_REQUEST,
	),
	REQUEST_SIZE_EXCEEDED(
		"COMMON_REQUEST_SIZE_EXCEEDED",
		"요청 용량 제한을 초과했습니다.",
		ErrorCategory.PAYLOAD_TOO_LARGE,
	),
	MEDIA_TYPE_UNSUPPORTED(
		"COMMON_MEDIA_TYPE_UNSUPPORTED",
		"지원하지 않는 미디어 타입입니다.",
		ErrorCategory.UNSUPPORTED_MEDIA_TYPE,
	),
	INTERNAL_SERVER_ERROR(
		"COMMON_INTERNAL_SERVER_ERROR",
		"서버 내부 오류가 발생했습니다.",
		ErrorCategory.INTERNAL_SERVER_ERROR,
	),
	;

	override val property: String? = null
}
