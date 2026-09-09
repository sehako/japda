package io.github.sehako.japda.product.presentation.image

import io.github.sehako.japda.product.application.image.ProductImageService
import io.github.sehako.japda.product.application.image.ProductImageUploadResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/products/{productId}/images")
class ProductImageController(
	private val productImageService: ProductImageService,
	private val requestConverter: UploadProductImagesRequestConverter,
) {

	@PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
	fun upload(
		@PathVariable productId: String,
		@RequestHeader(name = SELLER_ID_HEADER, required = false) sellerIdHeader: String?,
		@RequestPart(name = FILES_PART, required = false) files: List<MultipartFile>?,
		@RequestParam(name = REPRESENTATIVE_INDEX_PARAM, required = false) representativeIndex: String?,
	): ResponseEntity<ProductImageUploadResponse> {
		val dto = requestConverter.convert(productId, sellerIdHeader, files, representativeIndex)
		return ResponseEntity.status(HttpStatus.CREATED).body(productImageService.upload(dto))
	}

	companion object {
		private const val SELLER_ID_HEADER = "X-Seller-Id"
		private const val FILES_PART = "files"
		private const val REPRESENTATIVE_INDEX_PARAM = "representativeIndex"
	}
}
