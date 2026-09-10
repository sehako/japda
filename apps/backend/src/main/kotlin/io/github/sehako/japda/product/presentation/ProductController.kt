package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.product.application.ProductResponse
import io.github.sehako.japda.product.application.ProductService
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/products")
class ProductController(
	private val productService: ProductService,
) {
	@PostMapping
	fun create(
		@RequestHeader(name = SELLER_ID_HEADER, required = false) sellerIdHeader: String?,
		@RequestBody request: CreateProductRequest,
	): ResponseEntity<ProductResponse> {
		val sellerId = parseSellerId(sellerIdHeader)
		val response = productService.create(request.toDto(sellerId))
		return ResponseEntity
			.created(URI.create("/api/products/${response.id}"))
			.body(response)
	}

	private fun parseSellerId(value: String?): Long {
		if (value == null) {
			throw CommonException(CommonErrorCode.REQUEST_HEADER_MISSING)
		}
		return value.toLongOrNull()
			?: throw CommonException(CommonErrorCode.REQUEST_HEADER_INVALID)
	}

	companion object {
		private const val SELLER_ID_HEADER = "X-Seller-Id"
	}
}
