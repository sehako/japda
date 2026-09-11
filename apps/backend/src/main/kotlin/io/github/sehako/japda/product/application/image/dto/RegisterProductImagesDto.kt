package io.github.sehako.japda.product.application.image.dto

import io.github.sehako.japda.product.application.image.file.ProductImageFile

data class RegisterProductImagesDto(
	val productId: Long,
	val sellerId: Long,
	val files: List<ProductImageFile>,
	val representativeIndex: Int,
)
