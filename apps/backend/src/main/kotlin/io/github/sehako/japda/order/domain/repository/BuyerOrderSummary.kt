package io.github.sehako.japda.order.domain.repository

import io.github.sehako.japda.order.domain.model.OrderStatus
import java.time.Instant

data class BuyerOrderSummary(
	val orderId: Long,
	val status: OrderStatus,
	val productName: String,
	val quantity: Int,
	val unitPrice: Long,
	val totalPrice: Long,
	val createdAt: Instant,
	val expiresAt: Instant,
)
