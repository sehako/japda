package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.product.application.image.InvalidProductImageUploadException
import io.github.sehako.japda.product.application.image.ProductImagePayloadTooLargeException
import io.github.sehako.japda.product.application.image.ProductNotFoundException
import io.github.sehako.japda.product.domain.InvalidProductException
import io.github.sehako.japda.product.domain.image.ProductImageRegistrationConflictException
import io.github.sehako.japda.product.domain.image.ProductImageStorageException
import io.github.sehako.japda.product.presentation.image.ProductImageController
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [ProductController::class, ProductImageController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProductExceptionHandler {

	@ExceptionHandler(InvalidProductImageUploadException::class)
	fun handleInvalidProductImageUpload(
		exception: InvalidProductImageUploadException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.BAD_REQUEST,
		title = BAD_REQUEST_TITLE,
		detail = IMAGE_BAD_REQUEST_DETAIL,
		request = request,
		errors = exception.errors,
	)

	@ExceptionHandler(ProductImagePayloadTooLargeException::class)
	fun handleProductImagePayloadTooLarge(
		exception: ProductImagePayloadTooLargeException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.PAYLOAD_TOO_LARGE,
		title = PAYLOAD_TOO_LARGE_TITLE,
		detail = PAYLOAD_TOO_LARGE_DETAIL,
		request = request,
		errors = exception.errors,
	)

	@ExceptionHandler(ProductNotFoundException::class)
	fun handleProductNotFound(
		exception: ProductNotFoundException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.NOT_FOUND,
		title = NOT_FOUND_TITLE,
		detail = NOT_FOUND_DETAIL,
		request = request,
	)

	@ExceptionHandler(ProductImageRegistrationConflictException::class)
	fun handleProductImageConflict(
		exception: ProductImageRegistrationConflictException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.CONFLICT,
		title = CONFLICT_TITLE,
		detail = CONFLICT_DETAIL,
		request = request,
	)

	@ExceptionHandler(ProductImageStorageException::class)
	fun handleProductImageStorage(
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> {
		logger.error("상품 이미지 저장소 요청 처리 중 오류가 발생했습니다.")
		return problem(
			status = HttpStatus.SERVICE_UNAVAILABLE,
			title = SERVICE_UNAVAILABLE_TITLE,
			detail = SERVICE_UNAVAILABLE_DETAIL,
			request = request,
		)
	}

	@ExceptionHandler(InvalidProductException::class)
	fun handleInvalidProduct(
		exception: InvalidProductException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = badRequest(exception.errors, request)

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadableBody(
		exception: HttpMessageNotReadableException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = badRequest(
		errors = mapOf("request" to REQUEST_BODY_ERROR),
		request = request,
	)

	@ExceptionHandler(Exception::class)
	fun handleUnexpected(
		exception: Exception,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> {
		logger.error("상품 등록 요청 처리 중 예상하지 못한 오류가 발생했습니다.", exception)
		val problemDetail = ProblemDetail.forStatusAndDetail(
			HttpStatus.INTERNAL_SERVER_ERROR,
			INTERNAL_SERVER_ERROR_DETAIL,
		)
		problemDetail.title = INTERNAL_SERVER_ERROR_TITLE
		problemDetail.type = ABOUT_BLANK
		problemDetail.instance = URI.create(request.requestURI)

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail)
	}

	private fun badRequest(
		errors: Map<String, String>,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.BAD_REQUEST,
		title = BAD_REQUEST_TITLE,
		detail = BAD_REQUEST_DETAIL,
		request = request,
		errors = errors,
	)

	private fun problem(
		status: HttpStatus,
		title: String,
		detail: String,
		request: HttpServletRequest,
		errors: Map<String, String>? = null,
	): ResponseEntity<ProblemDetail> {
		val problemDetail = ProblemDetail.forStatusAndDetail(status, detail)
		problemDetail.title = title
		problemDetail.type = ABOUT_BLANK
		problemDetail.instance = URI.create(request.requestURI)
		errors?.let { problemDetail.setProperty("errors", it) }
		return ResponseEntity.status(status).body(problemDetail)
	}

	companion object {
		private val logger = LoggerFactory.getLogger(ProductExceptionHandler::class.java)
		private val ABOUT_BLANK = URI.create("about:blank")

		private const val BAD_REQUEST_TITLE = "요청 값이 올바르지 않습니다."
		private const val BAD_REQUEST_DETAIL = "상품 등록 요청을 확인해 주세요."
		private const val IMAGE_BAD_REQUEST_DETAIL = "상품 이미지 업로드 요청을 확인해 주세요."
		private const val REQUEST_BODY_ERROR = "요청 본문을 읽을 수 없습니다."
		private const val NOT_FOUND_TITLE = "상품을 찾을 수 없습니다."
		private const val NOT_FOUND_DETAIL = "요청한 상품을 찾을 수 없습니다."
		private const val CONFLICT_TITLE = "상품 이미지를 등록할 수 없습니다."
		private const val CONFLICT_DETAIL = "상품 이미지 등록 상태를 확인해 주세요."
		private const val PAYLOAD_TOO_LARGE_TITLE = "업로드 용량을 초과했습니다."
		private const val PAYLOAD_TOO_LARGE_DETAIL = "상품 이미지 업로드 용량을 확인해 주세요."
		private const val SERVICE_UNAVAILABLE_TITLE = "상품 이미지 저장소를 사용할 수 없습니다."
		private const val SERVICE_UNAVAILABLE_DETAIL = "잠시 후 다시 시도해 주세요."
		private const val INTERNAL_SERVER_ERROR_TITLE = "서버 오류가 발생했습니다."
		private const val INTERNAL_SERVER_ERROR_DETAIL = "요청 처리 중 오류가 발생했습니다."
	}
}
