package io.github.sehako.japda.global.error

import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler(
	private val problemDetailFactory: ProblemDetailFactory,
) {
	@ExceptionHandler(BusinessException::class)
	fun handleBusinessException(
		exception: BusinessException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = response(exception.errorCode, request)

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleHttpMessageNotReadableException(
		@Suppress("UNUSED_PARAMETER") exception: HttpMessageNotReadableException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = response(CommonErrorCode.REQUEST_BODY_MALFORMED, request)

	@ExceptionHandler(Exception::class)
	fun handleUnexpectedException(
		@Suppress("UNUSED_PARAMETER") exception: Exception,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = response(CommonErrorCode.INTERNAL_SERVER_ERROR, request)

	private fun response(
		errorCode: ErrorCode,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> {
		val problemDetail = problemDetailFactory.create(errorCode, request.requestURI)
		return ResponseEntity.status(problemDetail.status).body(problemDetail)
	}
}
