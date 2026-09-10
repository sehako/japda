package io.github.sehako.japda.product.application.image

data class RegisterProductImagesDto(
	val productId: Long,
	val sellerId: Long,
	val files: List<ProductImageFile>,
	val representativeIndex: Int,
)
