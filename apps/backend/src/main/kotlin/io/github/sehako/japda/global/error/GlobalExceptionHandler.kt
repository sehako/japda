package io.github.sehako.japda.global.error

import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.ErrorCode
import io.github.sehako.japda.auth.exception.AuthErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ProblemDetail
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException

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

	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handleMethodArgumentTypeMismatchException(
		@Suppress("UNUSED_PARAMETER") exception: MethodArgumentTypeMismatchException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = response(CommonErrorCode.REQUEST_PARAMETER_INVALID, request)

	@ExceptionHandler(MissingServletRequestParameterException::class)
	fun handleMissingServletRequestParameterException(
		@Suppress("UNUSED_PARAMETER") exception: MissingServletRequestParameterException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = response(CommonErrorCode.REQUEST_PARAMETER_INVALID, request)

	@ExceptionHandler(HttpMediaTypeNotSupportedException::class)
	fun handleHttpMediaTypeNotSupportedException(
		@Suppress("UNUSED_PARAMETER") exception: HttpMediaTypeNotSupportedException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = response(CommonErrorCode.MEDIA_TYPE_UNSUPPORTED, request)

	@ExceptionHandler(MaxUploadSizeExceededException::class)
	fun handleMaxUploadSizeExceededException(
		@Suppress("UNUSED_PARAMETER") exception: MaxUploadSizeExceededException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = response(CommonErrorCode.REQUEST_SIZE_EXCEEDED, request)

	@ExceptionHandler(MissingServletRequestPartException::class, MultipartException::class)
	fun handleMalformedMultipartException(
		@Suppress("UNUSED_PARAMETER") exception: Exception,
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
		val response = ResponseEntity.status(problemDetail.status)
		if (errorCode is AuthErrorCode) response.header(HttpHeaders.CACHE_CONTROL, "no-store")
		return response.body(problemDetail)
	}
}
