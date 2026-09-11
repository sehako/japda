package io.github.sehako.japda.order.application

import io.github.sehako.japda.order.domain.Order
import io.github.sehako.japda.order.domain.OrderStatus
import java.time.Instant

data class OrderResponse(
	val orderId: Long,
	val status: OrderStatus,
	val productName: String,
	val quantity: Int,
	val unitPrice: Long,
	val totalPrice: Long,
	val expiresAt: Instant,
)

internal fun Order.toResponse(): OrderResponse = OrderResponse(
	orderId = requireNotNull(id),
	status = status,
	productName = productName,
	quantity = quantity,
	unitPrice = unitPrice,
	totalPrice = totalPrice,
	expiresAt = expiresAt,
)
