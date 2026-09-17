package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.product.domain.model.Product
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.repository.ReadyProductQuery
import io.github.sehako.japda.product.domain.repository.ReadyProductSummary
import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName

@DisplayName("주문 생성 transaction 서비스")
class OrderCreationTransactionServiceTest {
	@Test
	@DisplayName("주문 저장 뒤 카운터를 확보하고 같은 식별자의 RESERVED 예약을 저장한다")
	fun 주문_저장_후_카운터_확보_같은_식별자의_RESERVED_예약을_저장한다() {
		val reservationRepository = RecordingReservationRepository()
		val counterRepository = RecordingCounterRepository(SaleInventoryReserveResult.ACQUIRED)

		val result = service(reservationRepository, counterRepository).create(request(), RESERVATION_ID)

		assertEquals(true, result.created)
		val reservation = assertNotNull(reservationRepository.saved)
		assertEquals(RESERVATION_ID, reservation.id)
		assertEquals(1L, reservation.orderId)
		assertEquals(2, reservation.quantity)
		assertEquals(InventoryReservationStatus.RESERVED, reservation.status)
		assertEquals(listOf(2), counterRepository.reservedQuantities)
	}

	@Test
	@DisplayName("조건부 카운터 확보가 재고 부족이면 기존 주문 오류로 거절한다")
	fun 조건부_카운터_확보_재고_부족_기존_주문_오류로_거절한다() {
		val exception = assertFailsWith<OrderException> {
			service(
				RecordingReservationRepository(),
				RecordingCounterRepository(SaleInventoryReserveResult.INSUFFICIENT),
			).create(request(), RESERVATION_ID)
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
	}

	@Test
	@DisplayName("판매 일정은 있지만 재고 카운터가 없으면 불변식 오류로 중단한다")
	fun 판매_일정_존재_재고_카운터_없음_불변식_오류로_중단한다() {
		val exception = assertFailsWith<IllegalStateException> {
			service(
				RecordingReservationRepository(),
				RecordingCounterRepository(SaleInventoryReserveResult.MISSING_COUNTER),
			).create(request(), RESERVATION_ID)
		}

		assertEquals("판매 일정의 재고 카운터를 찾을 수 없습니다.", exception.message)
	}

	private fun service(
		reservationRepository: RecordingReservationRepository,
		counterRepository: RecordingCounterRepository,
	): OrderCreationTransactionService {
		val orderRepository = RecordingOrderRepository()
		return OrderCreationTransactionService(
			orderRepository,
			StubSaleRepository(sale()),
			StubProductRepository(product()),
			reservationRepository,
			counterRepository,
			ExpiredInventoryReservationReleaseService(reservationRepository, counterRepository),
			Clock.fixed(NOW, ZoneOffset.UTC),
		)
	}

	private fun request() = OrderRequest.create(
		123L,
		UUID.randomUUID(),
		100L,
		2,
		"홍길동",
		"010-1234-5678",
		"06236",
		"서울",
		"101호",
		null,
	)

	private fun sale() = Sale.create(10L, 1L, LocalDate.parse("2026-09-11"), 35_000L, 10, NOW).also { setId(it, 100L) }
	private fun product() = Product.create(1L, "상품", null, NOW).also { setId(it, 10L) }

	private class RecordingOrderRepository : OrderRepository {
		override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order? = null
		override fun findByPaymentOrderId(paymentOrderId: String): Order? = null
		override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? = null
		override fun findById(id: Long): Order? = null
		override fun findSaleIdById(id: Long): Long? = null
		override fun markPaidIfPending(orderId: Long) = false
		override fun save(order: Order) = order.also { setId(it, 1L) }
	}

	private class RecordingReservationRepository : InventoryReservationRepository {
		var saved: InventoryReservation? = null
		override fun findByOrderId(orderId: Long): InventoryReservation? = null
		override fun findExpiredReservedBySaleId(saleId: Long, now: Instant) = emptyList<InventoryReservation>()
		override fun save(reservation: InventoryReservation) = reservation.also { saved = it }
		override fun markPaymentPendingIfReservedAndNotExpired(orderId: Long, now: Instant) = false
		override fun transitionById(id: UUID, expectedStatus: InventoryReservationStatus, targetStatus: InventoryReservationStatus, updatedAt: Instant) = false
		override fun transitionByOrderId(orderId: Long, expectedStatus: InventoryReservationStatus, targetStatus: InventoryReservationStatus, updatedAt: Instant) = false
	}

	private class RecordingCounterRepository(
		private val result: SaleInventoryReserveResult,
	) : SaleInventoryCounterRepository {
		val reservedQuantities = mutableListOf<Int>()
		override fun create(saleId: Long, now: Instant) = Unit
		override fun reserve(saleId: Long, quantity: Int, now: Instant): SaleInventoryReserveResult {
			reservedQuantities += quantity
			return result
		}
		override fun release(saleId: Long, quantity: Int, now: Instant) = true
		override fun findCommittedQuantity(saleId: Long): Int? = 0
	}

	private class StubSaleRepository(private val sale: Sale) : SaleRepository {
		override fun save(sale: Sale) = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate) = false
		override fun findQuantityById(id: Long): Int? = sale.quantity
		override fun findByIdForUpdate(id: Long): Sale? = error("주문 생성에서 판매 일정 잠금을 사용하면 안 됩니다.")
		override fun findById(id: Long): Sale? = sale
	}

	private class StubProductRepository(private val product: Product) : ProductRepository {
		override fun save(product: Product) = product
		override fun findById(id: Long): Product? = product
		override fun findByIdForUpdate(id: Long): Product? = product
		override fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary> = emptyList()
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
		val RESERVATION_ID: UUID = UUID.fromString("7f722e88-84e5-4ace-b25c-985b62304d65")
		fun setId(target: Any, id: Long) {
			target::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(target, id)
			}
		}
	}
}
