package io.github.sehako.japda.order.application.scheduler

import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName

@DisplayName("주문 만료 scheduler")
class ExpiredOrderSchedulerTest {
	@Test
	@DisplayName("결제 시도 없는 만료 주문을 EXPIRED로 바꾸고 판매 수량을 반환한다")
	fun 결제_시도_없는_만료_주문을_EXPIRED로_바꾸고_판매_수량을_반환한다() {
		val order = order()
		val orders = RecordingOrderRepository(order)
		val sales = RecordingSaleRepository()

		ExpiredOrderScheduler(orders, sales, Clock.fixed(NOW, ZoneOffset.UTC)).expire()

		assertEquals(1, orders.expiredCount)
		assertEquals(listOf(2), sales.decreasedQuantities)
	}

	@Test
	@DisplayName("이미 만료 처리된 주문은 다음 실행에서 수량을 다시 반환하지 않는다")
	fun 이미_만료_처리된_주문은_수량을_다시_반환하지_않는다() {
		val orders = RecordingOrderRepository(order(), expireOnlyOnce = true)
		val sales = RecordingSaleRepository()
		val scheduler = ExpiredOrderScheduler(orders, sales, Clock.fixed(NOW, ZoneOffset.UTC))

		scheduler.expire()
		scheduler.expire()

		assertEquals(1, orders.expiredCount)
		assertEquals(listOf(2), sales.decreasedQuantities)
	}

	private fun order(): Order = Order.create(
		OrderRequest.create(123L, UUID.randomUUID(), 100L, 2, "홍길동", "010-1234-5678", "06236", "서울", "101호", null),
		"상품",
		35_000L,
		NOW.minusSeconds(181),
	).also { setId(it, 1L) }

	private class RecordingOrderRepository(
		private val order: Order,
		private val expireOnlyOnce: Boolean = false,
	) : OrderRepository {
		var expiredCount = 0
		override fun findExpiredPendingWithoutPayment(now: Instant) = listOf(order)
		override fun markExpiredIfPendingWithoutPayment(orderId: Long, now: Instant): Boolean =
			if (!expireOnlyOnce || expiredCount == 0) {
				expiredCount++
				true
			} else false
		override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order? = null
		override fun findByPaymentOrderId(paymentOrderId: String): Order? = null
		override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? = null
		override fun findById(id: Long): Order? = null
		override fun findSaleIdById(id: Long): Long? = null
		override fun markPaidIfPending(orderId: Long) = false
		override fun markPaymentFailedIfPending(orderId: Long) = false
		override fun save(order: Order) = order
	}

	private class RecordingSaleRepository : SaleRepository {
		val decreasedQuantities = mutableListOf<Int>()
		override fun increaseCommittedQuantity(saleId: Long, quantity: Int) = error("사용하지 않아야 합니다.")
		override fun decreaseCommittedQuantity(saleId: Long, quantity: Int): Boolean = true.also { decreasedQuantities += quantity }
		override fun save(sale: Sale) = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate) = false
		override fun findById(id: Long): Sale? = null
		override fun findByIdForUpdate(id: Long): Sale? = null
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")

		fun setId(target: Any, id: Long) {
			target::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(target, id)
			}
		}
	}
}
