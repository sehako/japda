package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.Order
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OrderJpaRepository : JpaRepository<Order, Long> {
	fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order?

	fun findByPaymentOrderId(paymentOrderId: String): Order?

	@Query("select o.saleId from Order o where o.paymentOrderId = :paymentOrderId and o.buyerId = :buyerId")
	fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long?

	@Query("select o.saleId from Order o where o.id = :id")
	fun findSaleIdById(id: Long): Long?

	@Query(
		"""select coalesce(sum(o.quantity), 0) from Order o
			where o.saleId = :saleId and (
				o.status = io.github.sehako.japda.order.domain.model.OrderStatus.PAID
				or (o.status = io.github.sehako.japda.order.domain.model.OrderStatus.PENDING_PAYMENT
					and (o.expiresAt > :now or exists (
						select p.id from Payment p where p.orderId = o.id
						and p.status in (io.github.sehako.japda.payment.domain.model.PaymentStatus.CONFIRMING,
							io.github.sehako.japda.payment.domain.model.PaymentStatus.REVIEW_REQUIRED)
					))
					and not exists (select p.id from Payment p where p.orderId = o.id
						and p.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.FAILED)
				)
			)""",
	)
	fun sumCommittedQuantity(@Param("saleId") saleId: Long, @Param("now") now: Instant): Long
}
