package io.github.sehako.japda.order.application.dto

data class ListBuyerOrdersDto(
	val buyerId: Long,
	val cursor: String?,
	val size: Int,
)
