package io.github.sehako.japda.product.application.dto

data class ListReadyProductsDto(
	val sellerId: Long,
	val sort: String,
	val cursor: String?,
	val size: Int,
)
