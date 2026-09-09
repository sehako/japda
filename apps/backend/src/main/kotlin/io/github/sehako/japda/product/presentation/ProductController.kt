package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.product.application.ProductResponse
import io.github.sehako.japda.product.application.ProductService
import java.net.URI
import org.springframework.http.MediaType
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
	private val requestConverter: CreateProductRequestConverter,
) {

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun create(
		@RequestHeader(name = SELLER_ID_HEADER, required = false) sellerIdHeader: String?,
		@RequestBody request: CreateProductRequest,
	): ResponseEntity<ProductResponse> {
		val response = productService.create(requestConverter.convert(sellerIdHeader, request))

		return ResponseEntity.created(URI.create("/api/products/${response.id}"))
			.body(response)
	}

	companion object {
		private const val SELLER_ID_HEADER = "X-Seller-Id"
	}
}
