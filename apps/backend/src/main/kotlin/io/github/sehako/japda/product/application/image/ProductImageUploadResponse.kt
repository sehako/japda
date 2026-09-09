package io.github.sehako.japda.product.application.image

import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.ProductStatus

data class ProductImageUploadResponse(
	val productId: Long,
	val status: ProductStatus,
	val images: List<ProductImageResponse>,
)

data class ProductImageResponse(
	val id: Long,
	val sortOrder: Int,
	val representative: Boolean,
	val contentType: String,
	val sizeBytes: Long,
) {
	companion object {
		fun from(image: ProductImage): ProductImageResponse = ProductImageResponse(
			id = checkNotNull(image.id) { "저장된 상품 이미지에는 ID가 있어야 합니다." },
			sortOrder = image.sortOrder,
			representative = image.representative,
			contentType = image.contentType,
			sizeBytes = image.sizeBytes,
		)
	}
}
