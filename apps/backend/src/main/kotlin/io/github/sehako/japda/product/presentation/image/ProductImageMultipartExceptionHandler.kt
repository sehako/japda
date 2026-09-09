package io.github.sehako.japda.product.presentation.image

import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProductImageMultipartExceptionHandler {

	@ExceptionHandler(MaxUploadSizeExceededException::class)
	fun handleMaxUploadSizeExceeded(
		request: HttpServletRequest,
	): ResponseEntity<ProblemDetail> {
		val problemDetail = ProblemDetail.forStatusAndDetail(
			HttpStatus.PAYLOAD_TOO_LARGE,
			PAYLOAD_TOO_LARGE_DETAIL,
		)
		problemDetail.title = PAYLOAD_TOO_LARGE_TITLE
		problemDetail.type = ABOUT_BLANK
		problemDetail.instance = URI.create(request.requestURI)
		problemDetail.setProperty("errors", mapOf("files" to PAYLOAD_TOO_LARGE_FILES_ERROR))

		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problemDetail)
	}

	companion object {
		private val ABOUT_BLANK = URI.create("about:blank")

		private const val PAYLOAD_TOO_LARGE_TITLE = "업로드 용량을 초과했습니다."
		private const val PAYLOAD_TOO_LARGE_DETAIL = "상품 이미지 업로드 용량을 확인해 주세요."
		private const val PAYLOAD_TOO_LARGE_FILES_ERROR = "상품 이미지 업로드 용량 제한을 초과했습니다."
	}
}
