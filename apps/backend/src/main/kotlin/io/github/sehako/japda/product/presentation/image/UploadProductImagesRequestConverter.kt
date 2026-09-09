package io.github.sehako.japda.product.presentation.image

import io.github.sehako.japda.product.application.image.InvalidProductImageUploadException
import io.github.sehako.japda.product.application.image.ProductImageUploadFile
import io.github.sehako.japda.product.application.image.UploadProductImagesDto
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile

@Component
class UploadProductImagesRequestConverter {

	fun convert(
		productIdValue: String?,
		sellerIdValue: String?,
		files: List<MultipartFile>?,
		representativeIndexValue: String?,
	): UploadProductImagesDto {
		val errors = linkedMapOf<String, String>()
		val productId = positiveLong(productIdValue)
			?: run {
				errors[PRODUCT_ID_FIELD] = PRODUCT_ID_ERROR
				INVALID_LONG
			}
		val sellerId = positiveLong(sellerIdValue)
			?: run {
				errors[SELLER_ID_FIELD] = SELLER_ID_ERROR
				INVALID_LONG
			}
		val representativeIndex = representativeIndexValue?.toIntOrNull()
			?: run {
				errors[REPRESENTATIVE_INDEX_FIELD] = REPRESENTATIVE_INDEX_ERROR
				INVALID_INDEX
			}

		if (errors.isNotEmpty()) {
			throw InvalidProductImageUploadException(errors)
		}

		return UploadProductImagesDto(
			productId = productId,
			sellerId = sellerId,
			files = files.orEmpty().map { file ->
				ProductImageUploadFile(
					contentType = file.contentType,
					sizeBytes = file.size,
					openStream = { file.inputStream },
				)
			},
			representativeIndex = representativeIndex,
		)
	}

	private fun positiveLong(value: String?): Long? = value?.toLongOrNull()?.takeIf { it > 0 }

	companion object {
		private const val INVALID_LONG = -1L
		private const val INVALID_INDEX = -1
		private const val PRODUCT_ID_FIELD = "productId"
		private const val SELLER_ID_FIELD = "sellerId"
		private const val REPRESENTATIVE_INDEX_FIELD = "representativeIndex"
		private const val PRODUCT_ID_ERROR = "상품 ID는 1 이상의 정수여야 합니다."
		private const val SELLER_ID_ERROR = "판매자 ID는 1 이상의 정수여야 합니다."
		private const val REPRESENTATIVE_INDEX_ERROR = "대표 이미지 인덱스는 정수여야 합니다."
	}
}
