package io.github.sehako.japda.product.presentation.image.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.product.application.image.response.ProductImageRegistrationResponse
import io.github.sehako.japda.product.application.image.service.ProductImageRegistrationService
import io.github.sehako.japda.product.application.image.dto.RegisterProductImagesDto
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import io.github.sehako.japda.product.presentation.image.adapter.MultipartProductImageFile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartHttpServletRequest

@RestController
@RequestMapping("/api/products")
class ProductImageController(
	private val productImageRegistrationService: ProductImageRegistrationService,
	private val principalIdentityService: PrincipalIdentityService,
) {
	@PostMapping("/{productId}/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
	@ResponseStatus(HttpStatus.CREATED)
	fun register(
		@PathVariable productId: Long,
		@AuthenticationPrincipal userId: Long?,
		request: MultipartHttpServletRequest,
	): ProductImageRegistrationResponse {
		val sellerId = principalIdentityService.sellerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED))
		val representativeIndex = parseRepresentativeIndex(request)
		return productImageRegistrationService.register(
			RegisterProductImagesDto(
				productId = productId,
				sellerId = sellerId,
				files = request.getFiles(FILES_PART).map(::MultipartProductImageFile),
				representativeIndex = representativeIndex,
			),
		)
	}

	private fun parseRepresentativeIndex(request: MultipartHttpServletRequest): Int {
		val partValues = request.parts
			.filter { it.name == REPRESENTATIVE_INDEX_PART }
			.map { part -> part.inputStream.bufferedReader().use { it.readText() } }
		val values = when {
			partValues.isNotEmpty() -> partValues
			request.parameterMap.containsKey(REPRESENTATIVE_INDEX_PART) ->
				request.getParameterValues(REPRESENTATIVE_INDEX_PART).toList()
			else -> request.getFiles(REPRESENTATIVE_INDEX_PART)
				.map { file -> file.inputStream.bufferedReader().use { it.readText() } }
		}
		if (values.size != 1) {
			throw ProductException(ProductErrorCode.IMAGE_REPRESENTATIVE_INVALID)
		}
		return values.single().toIntOrNull()
			?: throw ProductException(ProductErrorCode.IMAGE_REPRESENTATIVE_INVALID)
	}

	companion object {
		private const val FILES_PART = "files"
		private const val REPRESENTATIVE_INDEX_PART = "representativeIndex"
	}
}
