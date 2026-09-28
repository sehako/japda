package io.github.sehako.japda.payment.application.service

import io.github.sehako.japda.auth.domain.repository.PrincipalIdentityRepository
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import io.github.sehako.japda.payment.domain.model.Payment
import io.github.sehako.japda.payment.domain.model.PaymentStatus
import io.github.sehako.japda.payment.domain.repository.PaymentRepository
import io.github.sehako.japda.payment.exception.PaymentErrorCode
import io.github.sehako.japda.payment.exception.PaymentException
import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import io.github.sehako.japda.settlement.domain.model.SettlementEntry
import io.github.sehako.japda.settlement.domain.repository.SettlementEntryRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("결제 transaction")
class PaymentTransactionServiceTest {
	@Test
	@DisplayName("만료된 주문은 결제 준비를 거절한다")
	fun 만료된_주문은_결제_준비를_거절한다() {
		val now = Instant.parse("2026-09-28T06:03:00Z")
		val order = order(expiresAt = now.minusSeconds(1))
		val service = service(order, clock = Clock.fixed(now, ZoneOffset.UTC))

		val exception = assertFailsWith<PaymentException> {
			service.prepare(123L, order.paymentOrderId, "payment-key", order.totalPrice)
		}

		assertEquals(PaymentErrorCode.ORDER_EXPIRED, exception.errorCode)
	}

	@Test
	@DisplayName("종료 상태 주문은 결제 준비를 거절한다")
	fun 종료_상태_주문은_결제_준비를_거절한다() {
		listOf<Order.() -> Unit>({ markPaid() }, { markExpired() }, { markPaymentFailed() }).forEach { transition ->
			val now = Instant.parse("2026-09-28T06:00:00Z")
			val order = order(expiresAt = now.plusSeconds(180)).also(transition)
			val service = service(order, clock = Clock.fixed(now, ZoneOffset.UTC))

			val exception = assertFailsWith<PaymentException> {
				service.prepare(123L, order.paymentOrderId, "payment-key", order.totalPrice)
			}

			assertEquals(PaymentErrorCode.ORDER_EXPIRED, exception.errorCode)
		}
	}

	@Test
	@DisplayName("확정된 결제 실패는 주문 상태와 판매 수량을 한 번만 반환한다")
	fun 확정된_결제_실패는_주문_상태와_판매_수량을_한_번만_반환한다() {
		val now = Instant.parse("2026-09-28T06:00:00Z")
		val order = order(expiresAt = now.plusSeconds(180))
		val payment = Payment.create(requireNotNull(order.id), "payment-key", order.totalPrice, now).also { setField(it, "id", 1L) }
		val orders = RecordingOrderRepository(order)
		val sales = RecordingSaleRepository()
		val service = service(
			order = order,
			orders = orders,
			payments = RecordingPaymentRepository(payment),
			sales = sales,
			clock = Clock.fixed(now, ZoneOffset.UTC),
		)

		service.apply(requireNotNull(payment.id), TossPaymentResult.ConfirmedFailure)
		service.apply(requireNotNull(payment.id), TossPaymentResult.ConfirmedFailure)

		assertEquals(1, orders.paymentFailedCalls)
		assertEquals("PAYMENT_FAILED", order.status.name)
		assertEquals(listOf(order.quantity), sales.decreasedQuantities)
	}

	private fun service(
		order: Order,
		orders: OrderRepository = RecordingOrderRepository(order),
		payments: PaymentRepository = EmptyPaymentRepository,
		sales: SaleRepository = RecordingSaleRepository(),
		clock: Clock,
	): PaymentTransactionService = PaymentTransactionService(
		identities = EmptyPrincipalIdentityRepository,
		orders = orders,
		payments = payments,
		sales = sales,
		settlementEntries = EmptySettlementEntryRepository,
		clock = clock,
		reconcileInterval = Duration.ofSeconds(30),
	)

	private fun order(expiresAt: Instant): Order = Order.create(
		OrderRequest.create(
			buyerId = 123L,
			idempotencyKey = UUID.randomUUID(),
			saleId = 1L,
			quantity = 2,
			recipientName = "홍길동",
			phoneNumber = "010",
			postalCode = "06236",
			address = "서울",
			detailAddress = "101호",
			deliveryMessage = null,
		),
		productName = "상품",
		unitPrice = 35_000L,
		createdAt = expiresAt.minusSeconds(180),
	).also { setField(it, "id", 1L) }

	private object EmptyPrincipalIdentityRepository : PrincipalIdentityRepository {
		override fun findBuyerId(userId: Long): Long? = null
		override fun findSellerId(userId: Long): Long? = null
		override fun findUserIdBySellerId(sellerId: Long): Long? = null
		override fun createBuyerLink(userId: Long): Long = error("호출하지 않아야 합니다.")
	}

	private class RecordingOrderRepository(private val order: Order) : OrderRepository {
		var paymentFailedCalls = 0
		override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order? = null
		override fun findByPaymentOrderId(paymentOrderId: String): Order? = order.takeIf { it.paymentOrderId == paymentOrderId }
		override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? = null
		override fun findById(id: Long): Order? = order.takeIf { it.id == id }
		override fun findSaleIdById(id: Long): Long? = null
		override fun markPaidIfPending(orderId: Long): Boolean = false
		override fun markPaymentFailedIfPending(orderId: Long): Boolean {
			paymentFailedCalls++
			if (paymentFailedCalls == 1) order.markPaymentFailed()
			return paymentFailedCalls == 1
		}
		override fun save(order: Order): Order = order
	}

	private object EmptyPaymentRepository : PaymentRepository {
		override fun findByOrderId(orderId: Long): Payment? = null
		override fun findById(id: Long): Payment? = null
		override fun findOrderIdById(id: Long): Long? = null
		override fun findDue(now: Instant, limit: Int): List<Payment> = emptyList()
		override fun save(payment: Payment): Payment {
		setField(payment, "id", 1L)
		return payment
	}
		override fun approveIfConfirming(id: Long, approvedAt: Instant): Boolean = false
		override fun failIfConfirming(id: Long, checkedAt: Instant): Boolean = false
		override fun claimIfDue(id: Long, now: Instant, nextReconcileAt: Instant): Boolean = false
		override fun deferIfConfirming(id: Long, checkedAt: Instant, nextReconcileAt: Instant): Boolean = false
		override fun requireReviewIfConfirming(id: Long, checkedAt: Instant): Boolean = false
		override fun requireReviewIfOverdue(id: Long, requestedAtOrBefore: Instant, checkedAt: Instant): Boolean = false
	}

	private class RecordingPaymentRepository(private val payment: Payment) : PaymentRepository {
		override fun findByOrderId(orderId: Long): Payment? = payment.takeIf { it.orderId == orderId }
		override fun findById(id: Long): Payment? = payment.takeIf { it.id == id }
		override fun findOrderIdById(id: Long): Long? = payment.orderId.takeIf { payment.id == id }
		override fun findDue(now: Instant, limit: Int): List<Payment> = emptyList()
		override fun save(payment: Payment): Payment = payment
		override fun approveIfConfirming(id: Long, approvedAt: Instant): Boolean = false
		override fun failIfConfirming(id: Long, checkedAt: Instant): Boolean {
		if (payment.id != id || payment.status != PaymentStatus.CONFIRMING) return false
		payment.fail(checkedAt)
		return true
	}
		override fun claimIfDue(id: Long, now: Instant, nextReconcileAt: Instant): Boolean = false
		override fun deferIfConfirming(id: Long, checkedAt: Instant, nextReconcileAt: Instant): Boolean = false
		override fun requireReviewIfConfirming(id: Long, checkedAt: Instant): Boolean = false
		override fun requireReviewIfOverdue(id: Long, requestedAtOrBefore: Instant, checkedAt: Instant): Boolean = false
	}

	private class RecordingSaleRepository : SaleRepository {
		val decreasedQuantities = mutableListOf<Int>()
		override fun save(sale: Sale): Sale = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean = false
		override fun findById(id: Long): Sale? = null
		override fun findByIdForUpdate(id: Long): Sale? = null
		override fun decreaseCommittedQuantity(saleId: Long, quantity: Int): Boolean {
		decreasedQuantities += quantity
		return true
	}
	}

	private object EmptySettlementEntryRepository : SettlementEntryRepository {
		override fun findByPaymentId(paymentId: Long): SettlementEntry? = null
		override fun save(entry: SettlementEntry): SettlementEntry = entry
	}

	private companion object {
		fun setField(target: Any, name: String, value: Any?) {
			target.javaClass.getDeclaredField(name).apply {
				isAccessible = true
				set(target, value)
			}
		}
	}
}
