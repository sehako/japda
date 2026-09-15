package io.github.sehako.japda.product.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.product.application.dto.ListReadyProductsDto
import io.github.sehako.japda.product.application.response.ProductResponse
import io.github.sehako.japda.product.application.service.ProductService
import io.github.sehako.japda.product.application.response.ReadyProductPageResponse
import io.github.sehako.japda.product.presentation.request.CreateProductRequest
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/products")
class ProductController(
	private val productService: ProductService,
	private val principalIdentityService: PrincipalIdentityService,
) {
	@PostMapping
	fun create(
		@AuthenticationPrincipal userId: Long?,
		@RequestBody request: CreateProductRequest,
	): ResponseEntity<ProductResponse> {
		val sellerId = principalIdentityService.sellerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED))
		val response = productService.create(request.toDto(sellerId))
		return ResponseEntity
			.created(URI.create("/api/products/${response.id}"))
			.body(response)
	}

	@GetMapping("/ready")
	fun listReady(
		@AuthenticationPrincipal userId: Long?,
		@RequestParam(required = false) sort: String?,
		@RequestParam(required = false) cursor: String?,
		@RequestParam(required = false) size: String?,
	): ReadyProductPageResponse = productService.listReady(
		ListReadyProductsDto(
			sellerId = principalIdentityService.sellerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED)),
			sort = sort ?: DEFAULT_SORT,
			cursor = cursor,
			size = parseSize(size),
		),
	)

	private fun parseSize(value: String?): Int = value?.toIntOrNull()
		?: if (value == null) DEFAULT_SIZE else throw CommonException(CommonErrorCode.REQUEST_PARAMETER_INVALID)

	companion object {
		private const val DEFAULT_SORT = "latest"
		private const val DEFAULT_SIZE = 20
	}
}
