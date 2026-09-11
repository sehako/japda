package io.github.sehako.japda.product.application.image.response

import io.github.sehako.japda.product.domain.model.ProductStatus

data class ProductImageRegistrationResponse(
	val productId: Long,
	val status: ProductStatus,
	val images: List<ProductImageResponse>,
)

data class ProductImageResponse(
	val id: Long,
	val displayOrder: Int,
	val isRepresentative: Boolean,
)
