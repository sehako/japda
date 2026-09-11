package io.github.sehako.japda.order.infrastructure

import io.github.sehako.japda.order.domain.Order
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OrderJpaRepository : JpaRepository<Order, Long> {
	fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order?

	@Query(
		"""select coalesce(sum(o.quantity), 0) from Order o
			where o.saleId = :saleId and o.status = io.github.sehako.japda.order.domain.OrderStatus.PENDING_PAYMENT
			and o.expiresAt > :now""",
	)
	fun sumActiveReservedQuantity(@Param("saleId") saleId: Long, @Param("now") now: Instant): Long
}
