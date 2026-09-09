package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.sale.application.ProductNotReadyForSaleException
import io.github.sehako.japda.sale.application.SaleTargetProductNotFoundException
import io.github.sehako.japda.sale.domain.InvalidSaleException
import io.github.sehako.japda.sale.domain.SalePeriodConflictException
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [SaleController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class SaleExceptionHandler {

	@ExceptionHandler(InvalidSaleException::class)
	fun handleInvalidSale(
		exception: InvalidSaleException,
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

	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handleInvalidPathVariable(
		exception: MethodArgumentTypeMismatchException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = badRequest(
		errors = mapOf("productId" to PRODUCT_ID_FORMAT_ERROR),
		request = request,
	)

	@ExceptionHandler(SaleTargetProductNotFoundException::class)
	fun handleSaleTargetProductNotFound(
		exception: SaleTargetProductNotFoundException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.NOT_FOUND,
		title = NOT_FOUND_TITLE,
		detail = NOT_FOUND_DETAIL,
		request = request,
	)

	@ExceptionHandler(ProductNotReadyForSaleException::class)
	fun handleProductNotReady(
		exception: ProductNotReadyForSaleException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.CONFLICT,
		title = PRODUCT_NOT_READY_TITLE,
		detail = PRODUCT_NOT_READY_DETAIL,
		request = request,
	)

	@ExceptionHandler(SalePeriodConflictException::class)
	fun handleSalePeriodConflict(
		exception: SalePeriodConflictException,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> = problem(
		status = HttpStatus.CONFLICT,
		title = PERIOD_CONFLICT_TITLE,
		detail = PERIOD_CONFLICT_DETAIL,
		request = request,
	)

	@ExceptionHandler(Exception::class)
	fun handleUnexpected(
		exception: Exception,
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> {
		logger.error("판매 등록 요청 처리 중 예상하지 못한 오류가 발생했습니다.", exception)
		return problem(
			status = HttpStatus.INTERNAL_SERVER_ERROR,
			title = INTERNAL_SERVER_ERROR_TITLE,
			detail = INTERNAL_SERVER_ERROR_DETAIL,
			request = request,
		)
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
		private val logger = LoggerFactory.getLogger(SaleExceptionHandler::class.java)
		private val ABOUT_BLANK = URI.create("about:blank")

		private const val BAD_REQUEST_TITLE = "요청 값이 올바르지 않습니다."
		private const val BAD_REQUEST_DETAIL = "판매 등록 요청을 확인해 주세요."
		private const val REQUEST_BODY_ERROR = "요청 본문을 읽을 수 없습니다."
		private const val PRODUCT_ID_FORMAT_ERROR = "상품 ID는 1 이상의 정수여야 합니다."
		private const val NOT_FOUND_TITLE = "상품을 찾을 수 없습니다."
		private const val NOT_FOUND_DETAIL = "판매 대상 상품을 찾을 수 없습니다."
		private const val PRODUCT_NOT_READY_TITLE = "판매를 등록할 수 없습니다."
		private const val PRODUCT_NOT_READY_DETAIL = "상품의 판매 준비 상태를 확인해 주세요."
		private const val PERIOD_CONFLICT_TITLE = "판매 기간이 겹칩니다."
		private const val PERIOD_CONFLICT_DETAIL = "같은 상품의 기존 판매 기간을 확인해 주세요."
		private const val INTERNAL_SERVER_ERROR_TITLE = "서버 오류가 발생했습니다."
		private const val INTERNAL_SERVER_ERROR_DETAIL = "요청 처리 중 오류가 발생했습니다."
	}
}
