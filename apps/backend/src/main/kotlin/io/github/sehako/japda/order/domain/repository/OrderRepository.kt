package io.github.sehako.japda.order.domain.repository

import io.github.sehako.japda.order.domain.model.Order
import java.util.UUID

interface OrderRepository {
	fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order?

	fun findByPaymentOrderId(paymentOrderId: String): Order?

	fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long?

	fun findById(id: Long): Order?

	fun findSaleIdById(id: Long): Long?

	fun markPaidIfPending(orderId: Long): Boolean

	fun save(order: Order): Order
}
