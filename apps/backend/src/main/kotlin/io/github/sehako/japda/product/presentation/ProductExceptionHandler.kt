package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.product.domain.InvalidProductException
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

@RestControllerAdvice(assignableTypes = [ProductController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProductExceptionHandler {

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
	): ResponseEntity<ProblemDetail> {
		val problemDetail = ProblemDetail.forStatusAndDetail(
			HttpStatus.BAD_REQUEST,
			BAD_REQUEST_DETAIL,
		)
		problemDetail.title = BAD_REQUEST_TITLE
		problemDetail.type = ABOUT_BLANK
		problemDetail.instance = URI.create(request.requestURI)
		problemDetail.setProperty("errors", errors)

		return ResponseEntity.badRequest().body(problemDetail)
	}

	companion object {
		private val logger = LoggerFactory.getLogger(ProductExceptionHandler::class.java)
		private val ABOUT_BLANK = URI.create("about:blank")

		private const val BAD_REQUEST_TITLE = "요청 값이 올바르지 않습니다."
		private const val BAD_REQUEST_DETAIL = "상품 등록 요청을 확인해 주세요."
		private const val REQUEST_BODY_ERROR = "요청 본문을 읽을 수 없습니다."
		private const val INTERNAL_SERVER_ERROR_TITLE = "서버 오류가 발생했습니다."
		private const val INTERNAL_SERVER_ERROR_DETAIL = "요청 처리 중 오류가 발생했습니다."
	}
}
