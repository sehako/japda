package io.github.sehako.japda.product.application

data class CreateProductDto(
	val sellerId: Long,
	val name: String?,
	val description: String?,
)
