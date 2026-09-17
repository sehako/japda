package io.github.sehako.japda.order.domain.repository

import java.time.Instant

data class BuyerOrderCursorBoundary(
	val createdAt: Instant,
	val orderId: Long,
)
