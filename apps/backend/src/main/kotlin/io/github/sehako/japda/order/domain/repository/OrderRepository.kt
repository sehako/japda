package io.github.sehako.japda.order.domain.repository

import io.github.sehako.japda.order.domain.model.Order
import java.time.Instant
import java.util.UUID

interface OrderRepository {
	fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order?

	fun sumActiveReservedQuantity(saleId: Long, now: Instant): Long

	fun save(order: Order): Order
}
