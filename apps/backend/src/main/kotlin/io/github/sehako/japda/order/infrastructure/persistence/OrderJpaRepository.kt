package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.Order
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

}
