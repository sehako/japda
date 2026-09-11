package io.github.sehako.japda.order.domain

import java.time.Instant
import java.util.UUID

interface OrderRepository {
	fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order?

	fun sumActiveReservedQuantity(saleId: Long, now: Instant): Long

	fun save(order: Order): Order
}
