package io.github.sehako.japda.global.error

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component

@Component
class ProblemDetailFactory {
	fun create(errorCode: ErrorCode, requestUri: String): ProblemDetail {
		val status = errorCode.category.toHttpStatus()
		return ProblemDetail.forStatusAndDetail(status, errorCode.category.detail).apply {
			title = errorCode.category.title
			instance = URI.create(requestUri)
			setProperty("type", "about:blank")
			setProperty("code", errorCode.code)
			errorCode.property?.let { property ->
				setProperty("errors", mapOf(property to errorCode.message))
			}
		}
	}

	private fun ErrorCategory.toHttpStatus(): HttpStatus = when (this) {
		ErrorCategory.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
		ErrorCategory.FORBIDDEN -> HttpStatus.FORBIDDEN
		ErrorCategory.NOT_FOUND -> HttpStatus.NOT_FOUND
		ErrorCategory.CONFLICT -> HttpStatus.CONFLICT
		ErrorCategory.PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE
		ErrorCategory.UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
		ErrorCategory.INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
	}

	private val ErrorCategory.title: String
		get() = when (this) {
			ErrorCategory.INVALID_REQUEST -> "잘못된 요청"
			ErrorCategory.FORBIDDEN -> "접근 거부"
			ErrorCategory.NOT_FOUND -> "리소스를 찾을 수 없음"
			ErrorCategory.CONFLICT -> "요청 충돌"
			ErrorCategory.PAYLOAD_TOO_LARGE -> "요청 용량 초과"
			ErrorCategory.UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 미디어 타입"
			ErrorCategory.INTERNAL_SERVER_ERROR -> "서버 내부 오류"
		}

	private val ErrorCategory.detail: String
		get() = when (this) {
			ErrorCategory.INVALID_REQUEST -> "요청 값이 올바르지 않습니다."
			ErrorCategory.FORBIDDEN -> "요청한 리소스에 접근할 수 없습니다."
			ErrorCategory.NOT_FOUND -> "요청한 리소스를 찾을 수 없습니다."
			ErrorCategory.CONFLICT -> "현재 상태와 요청이 충돌합니다."
			ErrorCategory.PAYLOAD_TOO_LARGE -> "요청 용량이 허용 범위를 초과했습니다."
			ErrorCategory.UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 미디어 타입입니다."
			ErrorCategory.INTERNAL_SERVER_ERROR -> "서버 내부 오류가 발생했습니다."
		}
}
