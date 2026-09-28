package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.Order
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OrderJpaRepository : JpaRepository<Order, Long> {
	fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order?

	fun findByPaymentOrderId(paymentOrderId: String): Order?

	@Query("select o.saleId from Order o where o.paymentOrderId = :paymentOrderId and o.buyerId = :buyerId")
	fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long?

	@Query("select o.saleId from Order o where o.id = :id")
	fun findSaleIdById(id: Long): Long?

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Order order set order.status = io.github.sehako.japda.order.domain.model.OrderStatus.PAID
			where order.id = :orderId
			and order.status = io.github.sehako.japda.order.domain.model.OrderStatus.PENDING_PAYMENT""",
	)
	fun markPaidIfPending(@Param("orderId") orderId: Long): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Order order set order.status = io.github.sehako.japda.order.domain.model.OrderStatus.PAYMENT_FAILED
			where order.id = :orderId
			and order.status = io.github.sehako.japda.order.domain.model.OrderStatus.PENDING_PAYMENT""",
	)
	fun markPaymentFailedIfPending(@Param("orderId") orderId: Long): Int

	@Query(
		value = """SELECT orders.* FROM orders
			WHERE orders.status = 'PENDING_PAYMENT'
			  AND orders.expires_at <= :now
			  AND NOT EXISTS (SELECT 1 FROM payments WHERE payments.order_id = orders.id)
			ORDER BY orders.id""",
		nativeQuery = true,
	)
	fun findExpiredPendingWithoutPayment(@Param("now") now: Instant): List<Order>

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		value = """UPDATE orders
			SET status = 'EXPIRED'
			WHERE id = :orderId
			  AND status = 'PENDING_PAYMENT'
			  AND expires_at <= :now
			  AND NOT EXISTS (SELECT 1 FROM payments WHERE payments.order_id = orders.id)""",
		nativeQuery = true,
	)
	fun markExpiredIfPendingWithoutPayment(@Param("orderId") orderId: Long, @Param("now") now: Instant): Int

}
