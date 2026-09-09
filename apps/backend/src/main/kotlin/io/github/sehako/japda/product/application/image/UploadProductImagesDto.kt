package io.github.sehako.japda.product.application.image

data class UploadProductImagesDto(
	val productId: Long,
	val sellerId: Long,
	val files: List<ProductImageUploadFile>,
	val representativeIndex: Int,
)
