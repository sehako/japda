package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.application.response.toResponse
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.mockito.Mockito.mock

@DisplayName("주문 서비스")
class OrderServiceTest {
	@Test
	@DisplayName("기존 멱등 주문은 PostgreSQL 주문 transaction 없이 반환한다")
	fun 기존_멱등_주문_DB_transaction_없이_반환한다() {
		val transactionService = RecordingTransactionService()

		val response = service(RecordingOrderRepository(existingOrder()), transactionService).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(0, transactionService.createCount)
	}

	@Test
	@DisplayName("신규 주문은 임의 UUID로 PostgreSQL 주문 transaction을 실행한다")
	fun 신규_주문_임의_UUID로_DB_주문_transaction을_실행한다() {
		val transactionService = RecordingTransactionService(result = creationResult(created = true))

		val service = service(RecordingOrderRepository(), transactionService)

		val firstResponse = service.create(dto())
		val secondResponse = service.create(dto())

		assertEquals(1L, firstResponse.orderId)
		assertEquals(1L, secondResponse.orderId)
		assertEquals(2, transactionService.reservationIds.size)
		assertNotEquals(transactionService.reservationIds[0], transactionService.reservationIds[1])
	}

	@Test
	@DisplayName("DB 재고 부족은 기존 재고 부족 오류로 변환한다")
	fun DB_재고_부족_기존_재고_부족_오류로_변환한다() {
		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				RecordingTransactionService(failure = OrderInventoryInsufficientException(1)),
			).create(dto())
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
	}

	@Test
	@DisplayName("멱등 unique 충돌은 기존 주문으로 복구한다")
	fun 멱등_unique_충돌_기존_주문으로_복구한다() {
		val transactionService = RecordingTransactionService(
			failure = OrderIdempotencyPersistenceException(IllegalStateException("테스트 충돌")),
			recoveryResult = creationResult(orderId = 9L, created = false),
		)

		val response = service(RecordingOrderRepository(), transactionService).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(1, transactionService.recoverCount)
	}

	private fun service(
		orderRepository: OrderRepository,
		transactionService: OrderCreationTransactionService,
	) = OrderService(orderRepository, transactionService)

	private fun dto() = CreateOrderDto(
		buyerId = 123L,
		idempotencyKey = IDEMPOTENCY_KEY,
		saleId = 100L,
		quantity = 2,
		recipientName = "홍길동",
		phoneNumber = "010-1234-5678",
		postalCode = "06236",
		address = "서울특별시 강남구 테헤란로 123",
		detailAddress = "101동 1001호",
		deliveryMessage = null,
	)

	private fun existingOrder(): Order = Order.create(
		dto().toDomainRequest(),
		"서버 상품명",
		35_000L,
		NOW,
	).also { setId(it, 9L) }

	private fun creationResult(orderId: Long = 1L, created: Boolean): OrderCreationResult {
		val order = Order.create(dto().toDomainRequest(), "서버 상품명", 35_000L, NOW).also { setId(it, orderId) }
		return OrderCreationResult(order.toResponse(), created)
	}

	private class RecordingTransactionService(
		private val result: OrderCreationResult? = null,
		private val failure: RuntimeException? = null,
		private val recoveryResult: OrderCreationResult? = null,
	) : OrderCreationTransactionService(
		mock(OrderRepository::class.java),
		mock(SaleRepository::class.java),
		mock(ProductRepository::class.java),
		mock(InventoryReservationRepository::class.java),
		mock(SaleInventoryCounterRepository::class.java),
		mock(ExpiredInventoryReservationReleaseService::class.java),
		Clock.systemUTC(),
	) {
		var createCount = 0
		var recoverCount = 0
		val reservationIds = mutableListOf<UUID>()

		override fun create(request: OrderRequest, reservationId: UUID): OrderCreationResult {
			createCount++
			reservationIds += reservationId
			failure?.let { throw it }
			return requireNotNull(result)
		}

		override fun recoverIdempotentRequest(request: OrderRequest): OrderCreationResult {
			recoverCount++
			return requireNotNull(recoveryResult)
		}
	}

	private class RecordingOrderRepository(private val existing: Order? = null) : OrderRepository {
		override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID) = existing
		override fun findByPaymentOrderId(paymentOrderId: String): Order? = null
		override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? = null
		override fun findById(id: Long): Order? = null
		override fun findSaleIdById(id: Long): Long? = null
		override fun markPaidIfPending(orderId: Long) = false
		override fun save(order: Order) = order
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
		val IDEMPOTENCY_KEY: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

		fun setId(target: Any, id: Long) {
			target::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(target, id)
			}
		}
	}
}
