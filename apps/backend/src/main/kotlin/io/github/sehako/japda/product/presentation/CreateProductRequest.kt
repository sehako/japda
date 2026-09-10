package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.product.application.CreateProductDto

data class CreateProductRequest(
	val name: String?,
	val description: String? = null,
) {
	fun toDto(sellerId: Long): CreateProductDto = CreateProductDto(
		sellerId = sellerId,
		name = name,
		description = description,
	)
}
