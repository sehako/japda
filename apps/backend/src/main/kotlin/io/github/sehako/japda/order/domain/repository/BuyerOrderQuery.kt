package io.github.sehako.japda.order.domain.repository

data class BuyerOrderQuery(
	val buyerId: Long,
	val cursor: BuyerOrderCursorBoundary?,
	val limit: Int,
)
