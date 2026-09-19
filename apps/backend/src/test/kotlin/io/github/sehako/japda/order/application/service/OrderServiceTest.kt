package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.application.inventory.SoldOutInventoryMarker
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
	@DisplayName("기존 멱등 주문은 품절 마커를 조회하지 않고 반환한다")
	fun 기존_멱등_주문_품절_마커_조회_없이_반환한다() {
		val marker = RecordingSoldOutInventoryMarker(soldOut = true)
		val transactionService = RecordingTransactionService()

		val response = service(RecordingOrderRepository(existingOrder()), transactionService, marker).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(0, marker.checkCount)
		assertEquals(0, transactionService.createCount)
	}

	@Test
	@DisplayName("품절 마커가 있으면 DB transaction 없이 기존 재고 부족 오류를 반환한다")
	fun 품절_마커_존재_DB_transaction_없이_재고_부족을_반환한다() {
		val marker = RecordingSoldOutInventoryMarker(soldOut = true)
		val transactionService = RecordingTransactionService()

		val exception = assertFailsWith<OrderException> {
			service(RecordingOrderRepository(), transactionService, marker).create(dto())
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertEquals(1, marker.checkCount)
		assertEquals(0, transactionService.createCount)
	}

	@Test
	@DisplayName("품절 마커가 없으면 임의 UUID로 DB 주문 transaction을 실행한다")
	fun 품절_마커_없음_임의_UUID로_DB_주문_transaction을_실행한다() {
		val transactionService = RecordingTransactionService(result = creationResult(created = true))

		val service = service(
			RecordingOrderRepository(),
			transactionService,
			RecordingSoldOutInventoryMarker(soldOut = false),
		)

		val firstResponse = service.create(dto())
		val secondResponse = service.create(dto())

		assertEquals(1L, firstResponse.orderId)
		assertEquals(1L, secondResponse.orderId)
		assertEquals(2, transactionService.reservationIds.size)
		assertNotEquals(transactionService.reservationIds[0], transactionService.reservationIds[1])
	}

	@Test
	@DisplayName("품절 마커 조회 오류는 DB 주문 경로로 우회한다")
	fun 품절_마커_조회_오류_DB_주문_경로로_우회한다() {
		val transactionService = RecordingTransactionService(result = creationResult(created = true))

		val response = service(
			RecordingOrderRepository(),
			transactionService,
			RecordingSoldOutInventoryMarker(checkFailure = IllegalStateException("테스트 조회 실패")),
		).create(dto())

		assertEquals(1L, response.orderId)
		assertEquals(1, transactionService.createCount)
	}

	@Test
	@DisplayName("DB가 완전 품절을 확인하면 rollback 뒤 품절 마커를 기록한다")
	fun DB_완전_품절_rollback_후_품절_마커를_기록한다() {
		val marker = RecordingSoldOutInventoryMarker()

		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				RecordingTransactionService(failure = OrderInventoryInsufficientException(0)),
				marker,
			).create(dto())
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertEquals(listOf(100L), marker.markedSaleIds)
	}

	@Test
	@DisplayName("DB 잔여 재고가 양수이면 품절 마커를 기록하지 않는다")
	fun DB_잔여_재고_양수_품절_마커를_기록하지_않는다() {
		val marker = RecordingSoldOutInventoryMarker()

		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				RecordingTransactionService(failure = OrderInventoryInsufficientException(1)),
				marker,
			).create(dto())
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertEquals(emptyList(), marker.markedSaleIds)
	}

	@Test
	@DisplayName("품절 마커 기록 실패는 DB 재고 부족 결과를 변경하지 않는다")
	fun 품절_마커_기록_실패_DB_재고_부족_결과를_변경하지_않는다() {
		val marker = RecordingSoldOutInventoryMarker(markFailure = IllegalStateException("테스트 기록 실패"))

		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				RecordingTransactionService(failure = OrderInventoryInsufficientException(0)),
				marker,
			).create(dto())
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertEquals(listOf(100L), marker.markedSaleIds)
	}

	@Test
	@DisplayName("멱등 unique 충돌을 기존 주문으로 복구하면 품절 마커를 기록하지 않는다")
	fun 멱등_unique_충돌_기존_주문_복구_품절_마커를_기록하지_않는다() {
		val marker = RecordingSoldOutInventoryMarker()
		val transactionService = RecordingTransactionService(
			failure = OrderIdempotencyPersistenceException(IllegalStateException("테스트 충돌")),
			recoveryResult = creationResult(orderId = 9L, created = false),
		)

		val response = service(RecordingOrderRepository(), transactionService, marker).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(1, transactionService.recoverCount)
		assertEquals(emptyList(), marker.markedSaleIds)
	}

	@Test
	@DisplayName("DB 주문의 다른 오류에는 품절 마커를 기록하지 않는다")
	fun DB_주문_다른_오류_품절_마커를_기록하지_않는다() {
		val marker = RecordingSoldOutInventoryMarker()

		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				RecordingTransactionService(failure = OrderException(OrderErrorCode.SALE_NOT_OPEN)),
				marker,
			).create(dto())
		}

		assertEquals(OrderErrorCode.SALE_NOT_OPEN, exception.errorCode)
		assertEquals(emptyList(), marker.markedSaleIds)
	}

	private fun service(
		orderRepository: OrderRepository,
		transactionService: OrderCreationTransactionService,
		marker: SoldOutInventoryMarker,
	) = OrderService(orderRepository, transactionService, marker)

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

	private class RecordingSoldOutInventoryMarker(
		private val soldOut: Boolean = false,
		private val checkFailure: RuntimeException? = null,
		private val markFailure: RuntimeException? = null,
	) : SoldOutInventoryMarker {
		var checkCount = 0
		val markedSaleIds = mutableListOf<Long>()

		override fun isSoldOut(saleId: Long): Boolean {
			checkCount++
			checkFailure?.let { throw it }
			return soldOut
		}

		override fun markSoldOut(saleId: Long) {
			markedSaleIds += saleId
			markFailure?.let { throw it }
		}
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
