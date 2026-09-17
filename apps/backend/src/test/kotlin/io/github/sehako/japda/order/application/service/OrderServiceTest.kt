package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.inventory.InventoryReservation
import io.github.sehako.japda.order.application.inventory.InventoryReservationResult
import io.github.sehako.japda.order.application.inventory.InventoryReservationToken
import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
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
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName

@DisplayName("주문 서비스")
class OrderServiceTest {
	@Test
	@DisplayName("판매 중인 상품을 주문하면 판매 일정 잠금 없이 서버 원본으로 주문을 저장한다")
	fun 판매_중인_상품_주문_판매_일정_잠금_없이_서버_원본으로_주문을_저장한다() {
		val orderRepository = RecordingOrderRepository(reservedQuantity = 3)
		val saleRepository = StubSaleRepository(sale(quantity = 10, price = 35_000L))
		val service = service(orderRepository, saleRepository)

		val response = service.create(dto(quantity = 2))

		assertEquals("서버 상품명", response.productName)
		assertEquals(35_000L, response.unitPrice)
		assertEquals(70_000L, response.totalPrice)
		assertEquals(NOW, orderRepository.savedOrder?.createdAt)
		assertEquals(0, saleRepository.lockCount)
	}

	@Test
	@DisplayName("같은 멱등성 키와 동일 요청이면 판매 잠금 없이 기존 주문을 반환한다")
	fun 같은_멱등성_키_동일_요청_판매_잠금_없이_기존_주문을_반환한다() {
		val request = dto().toDomainRequest()
		val existing = Order.create(request, "서버 상품명", 35_000L, NOW).also { setId(it, 9L) }
		val orderRepository = RecordingOrderRepository(existing = existing)
		val saleRepository = StubSaleRepository(sale())

		val inventoryReservation = RecordingInventoryReservation()
		val response = service(orderRepository, saleRepository, inventoryReservation).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(existing.paymentOrderId, response.paymentOrderId)
		assertEquals(NOW.plusSeconds(180), response.expiresAt)
		assertEquals(0, saleRepository.lockCount)
		assertEquals(0, inventoryReservation.reserveCount)
		assertNull(orderRepository.savedOrder)
	}

	@Test
	@DisplayName("Redis 선점에 성공하고 신규 주문이 생성되면 예약을 유지한다")
	fun Redis_선점_성공_신규_주문_생성_예약을_유지한다() {
		val inventoryReservation = RecordingInventoryReservation(InventoryReservationResult.Reserved(TOKEN))
		val databaseReservationRepository = RecordingDatabaseReservationRepository()

		val response = service(
			RecordingOrderRepository(),
			StubSaleRepository(sale()),
			inventoryReservation,
			databaseReservationRepository,
		).create(dto())

		assertEquals(1L, response.orderId)
		assertEquals(1, inventoryReservation.reserveCount)
		assertEquals(0, inventoryReservation.restoreCount)
		assertEquals(0, inventoryReservation.invalidateCount)
		assertEquals(UUID.fromString(TOKEN.reservationId), databaseReservationRepository.saved?.id)
	}

	@Test
	@DisplayName("Redis가 우회를 반환하면 DB 주문을 생성한다")
	fun Redis_우회_반환_DB_주문을_생성한다() {
		val inventoryReservation = RecordingInventoryReservation(InventoryReservationResult.Fallback)
		val orderRepository = RecordingOrderRepository()

		val response = service(orderRepository, StubSaleRepository(sale()), inventoryReservation).create(dto())

		assertEquals(1L, response.orderId)
		assertEquals(1, inventoryReservation.reserveCount)
		assertEquals(1L, orderRepository.savedOrder?.id)
	}

	@Test
	@DisplayName("Redis가 재고 부족을 반환하면 DB transaction 없이 주문을 거절한다")
	fun Redis_재고_부족_반환_DB_transaction_없이_주문을_거절한다() {
		val saleRepository = StubSaleRepository(sale())
		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				saleRepository,
				RecordingInventoryReservation(InventoryReservationResult.Insufficient),
			).create(dto())
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertEquals(0, saleRepository.lockCount)
	}

	@Test
	@DisplayName("Redis 선점 후 DB가 재고 부족을 확정하면 현재 세대를 폐기한다")
	fun Redis_선점_후_DB_재고_부족_확정_현재_세대를_폐기한다() {
		val inventoryReservation = RecordingInventoryReservation(InventoryReservationResult.Reserved(TOKEN))
		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(reservedQuantity = 9),
				StubSaleRepository(sale(quantity = 10)),
				inventoryReservation,
			).create(dto(quantity = 2))
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertEquals(0, inventoryReservation.restoreCount)
		assertEquals(1, inventoryReservation.invalidateCount)
	}

	@Test
	@DisplayName("Redis 선점 후 주문이 생성되지 않으면 예약을 복원한다")
	fun Redis_선점_후_주문_미생성_예약을_복원한다() {
		val inventoryReservation = RecordingInventoryReservation(InventoryReservationResult.Reserved(TOKEN))
		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				StubSaleRepository(sale(saleDate = LocalDate.parse("2026-09-10"))),
				inventoryReservation,
			).create(dto())
		}

		assertEquals(OrderErrorCode.SALE_NOT_OPEN, exception.errorCode)
		assertEquals(1, inventoryReservation.restoreCount)
		assertEquals(0, inventoryReservation.invalidateCount)
	}

	@Test
	@DisplayName("DB transaction 재확인에서 기존 멱등 주문을 반환하면 Redis 예약을 복원한다")
	fun DB_transaction_재확인_기존_멱등_주문_반환_Redis_예약을_복원한다() {
		val request = dto().toDomainRequest()
		val existing = Order.create(request, "서버 상품명", 35_000L, NOW).also { setId(it, 9L) }
		val orderRepository = RecordingOrderRepository(existingResults = listOf(null, existing))
		val inventoryReservation = RecordingInventoryReservation(InventoryReservationResult.Reserved(TOKEN))

		val response = service(orderRepository, StubSaleRepository(sale()), inventoryReservation).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(1, inventoryReservation.restoreCount)
	}

	@Test
	@DisplayName("멱등 unique 충돌을 기존 주문으로 복구하면 Redis 예약을 복원한다")
	fun 멱등_unique_충돌_기존_주문_복구_Redis_예약을_복원한다() {
		val request = dto().toDomainRequest()
		val existing = Order.create(request, "서버 상품명", 35_000L, NOW).also { setId(it, 9L) }
		val orderRepository = RecordingOrderRepository(
			existingResults = listOf(null, null, existing),
			saveFailure = OrderIdempotencyPersistenceException(IllegalStateException("테스트 충돌")),
		)
		val inventoryReservation = RecordingInventoryReservation(InventoryReservationResult.Reserved(TOKEN))

		val response = service(orderRepository, StubSaleRepository(sale()), inventoryReservation).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(1, inventoryReservation.restoreCount)
	}

	@Test
	@DisplayName("Redis 복원 실패는 DB 주문 거절 결과를 덮지 않는다")
	fun Redis_복원_실패_DB_주문_거절_결과를_덮지_않는다() {
		val inventoryReservation = RecordingInventoryReservation(
			result = InventoryReservationResult.Reserved(TOKEN),
			restoreFailure = IllegalStateException("테스트 복원 실패"),
		)

		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(),
				StubSaleRepository(sale(saleDate = LocalDate.parse("2026-09-10"))),
				inventoryReservation,
			).create(dto())
		}

		assertEquals(OrderErrorCode.SALE_NOT_OPEN, exception.errorCode)
		assertEquals(1, inventoryReservation.restoreCount)
	}

	@Test
	@DisplayName("Redis 폐기 실패는 DB 재고 부족 결과를 덮지 않는다")
	fun Redis_폐기_실패_DB_재고_부족_결과를_덮지_않는다() {
		val inventoryReservation = RecordingInventoryReservation(
			result = InventoryReservationResult.Reserved(TOKEN),
			invalidateFailure = IllegalStateException("테스트 폐기 실패"),
		)

		val exception = assertFailsWith<OrderException> {
			service(
				RecordingOrderRepository(reservedQuantity = 9),
				StubSaleRepository(sale(quantity = 10)),
				inventoryReservation,
			).create(dto(quantity = 2))
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertEquals(1, inventoryReservation.invalidateCount)
	}

	@Test
	@DisplayName("DB transaction에서 신규 주문을 저장하면 생성 결과를 반환한다")
	fun DB_transaction_신규_주문_저장_생성_결과를_반환한다() {
		val result = transactionService(RecordingOrderRepository(), StubSaleRepository(sale()))
			.create(dto().toDomainRequest(), UUID.randomUUID())

		assertEquals(true, result.created)
		assertEquals(1L, result.response.orderId)
	}

	@Test
	@DisplayName("DB transaction 재확인에서 기존 주문이 보이면 미생성 결과를 반환한다")
	fun DB_transaction_재확인_기존_주문_존재_미생성_결과를_반환한다() {
		val request = dto().toDomainRequest()
		val existing = Order.create(request, "서버 상품명", 35_000L, NOW).also { setId(it, 9L) }

		val result = transactionService(RecordingOrderRepository(existing = existing), StubSaleRepository(sale()))
			.create(request, UUID.randomUUID())

		assertEquals(false, result.created)
		assertEquals(9L, result.response.orderId)
	}

	@Test
	@DisplayName("같은 멱등성 키에 다른 요청이면 충돌로 거절한다")
	fun 같은_멱등성_키_다른_요청_충돌로_거절한다() {
		val existing = Order.create(dto(quantity = 1).toDomainRequest(), "상품", 1_000L, NOW).also { setId(it, 1L) }

		val exception = assertFailsWith<OrderException> {
			service(RecordingOrderRepository(existing = existing), StubSaleRepository(sale())).create(dto(quantity = 2))
		}

		assertEquals(OrderErrorCode.IDEMPOTENCY_CONFLICT, exception.errorCode)
	}

	@Test
	@DisplayName("남은 판매 수량보다 주문 수량이 많으면 거절한다")
	fun 남은_판매_수량보다_주문_수량이_많음_거절한다() {
		val exception = assertFailsWith<OrderException> {
			service(RecordingOrderRepository(reservedQuantity = 9), StubSaleRepository(sale(quantity = 10)))
				.create(dto(quantity = 2))
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
	}

	@Test
	@DisplayName("판매 종료 정각이면 주문을 거절한다")
	fun 판매_종료_정각_주문을_거절한다() {
		val endedSale = sale(saleDate = LocalDate.parse("2026-09-10"))

		val exception = assertFailsWith<OrderException> {
			service(RecordingOrderRepository(), StubSaleRepository(endedSale)).create(dto())
		}

		assertEquals(OrderErrorCode.SALE_NOT_OPEN, exception.errorCode)
	}

	private fun service(
		orderRepository: RecordingOrderRepository,
		saleRepository: StubSaleRepository,
		inventoryReservation: InventoryReservation = RecordingInventoryReservation(),
		databaseReservationRepository: RecordingDatabaseReservationRepository = RecordingDatabaseReservationRepository(),
	): OrderService {
		val transactionService = transactionService(orderRepository, saleRepository, databaseReservationRepository)
		return OrderService(orderRepository, transactionService, inventoryReservation)
	}

	private fun transactionService(
		orderRepository: RecordingOrderRepository,
		saleRepository: StubSaleRepository,
		databaseReservationRepository: RecordingDatabaseReservationRepository = RecordingDatabaseReservationRepository(),
	) = OrderCreationTransactionService(
		orderRepository,
		saleRepository,
		StubProductRepository(product()),
		databaseReservationRepository,
		RecordingCounterRepository(orderRepository.reservedQuantity, saleRepository.sale?.quantity),
		ExpiredInventoryReservationReleaseService(
			RecordingDatabaseReservationRepository(),
			RecordingCounterRepository(orderRepository.reservedQuantity, saleRepository.sale?.quantity),
		),
		Clock.fixed(NOW, ZoneOffset.UTC),
	)

	private fun dto(quantity: Int? = 2) = CreateOrderDto(
		buyerId = 123L,
		idempotencyKey = IDEMPOTENCY_KEY,
		saleId = 100L,
		quantity = quantity,
		recipientName = " 홍길동 ",
		phoneNumber = "010-1234-5678",
		postalCode = "06236",
		address = "서울특별시 강남구 테헤란로 123",
		detailAddress = "101동 1001호",
		deliveryMessage = " 문 앞 ",
	)

	private fun sale(
		quantity: Int = 10,
		price: Long = 35_000L,
		saleDate: LocalDate = LocalDate.parse("2026-09-11"),
	): Sale = Sale.create(10L, 1L, saleDate, price, quantity, NOW).also { setId(it, 100L) }

	private fun product(): Product = Product.create(1L, "서버 상품명", null, NOW).also { setId(it, 10L) }

	private class RecordingOrderRepository(
		existing: Order? = null,
		existingResults: List<Order?>? = null,
		val reservedQuantity: Long = 0,
		private val saveFailure: RuntimeException? = null,
	) : OrderRepository {
		private val existingResults = ArrayDeque(existingResults ?: listOf(existing))
		private val lastExisting = existingResults?.lastOrNull() ?: existing
		var savedOrder: Order? = null

		override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order? =
			if (existingResults.isEmpty()) lastExisting else existingResults.removeFirst()

		override fun findByPaymentOrderId(paymentOrderId: String): Order? = lastExisting?.takeIf { it.paymentOrderId == paymentOrderId }

		override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? =
			lastExisting?.takeIf { it.paymentOrderId == paymentOrderId && it.buyerId == buyerId }?.saleId

		override fun findById(id: Long): Order? = lastExisting?.takeIf { it.id == id }

		override fun findSaleIdById(id: Long): Long? = lastExisting?.takeIf { it.id == id }?.saleId

		override fun markPaidIfPending(orderId: Long): Boolean = false

		override fun save(order: Order): Order {
			saveFailure?.let { throw it }
			return order.also {
				setId(it, 1L)
				savedOrder = it
			}
		}
	}

	private class RecordingInventoryReservation(
		private val result: InventoryReservationResult = InventoryReservationResult.Fallback,
		private val restoreFailure: RuntimeException? = null,
		private val invalidateFailure: RuntimeException? = null,
	) : InventoryReservation {
		var reserveCount = 0
		var restoreCount = 0
		var invalidateCount = 0

		override fun reserve(saleId: Long, quantity: Int): InventoryReservationResult {
			reserveCount++
			return result
		}

		override fun restore(token: InventoryReservationToken) {
			restoreCount++
			restoreFailure?.let { throw it }
		}

		override fun invalidate(token: InventoryReservationToken) {
			invalidateCount++
			invalidateFailure?.let { throw it }
		}
	}

	private class StubSaleRepository(val sale: Sale?) : SaleRepository {
		var lockCount = 0
		override fun save(sale: Sale): Sale = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate) = false
		override fun findById(id: Long): Sale? = sale
		override fun findByIdForUpdate(id: Long): Sale? = sale.also { lockCount++ }
	}

	private class RecordingDatabaseReservationRepository : InventoryReservationRepository {
		var saved: io.github.sehako.japda.order.domain.model.InventoryReservation? = null
		override fun findByOrderId(orderId: Long) = null
		override fun findExpiredReservedBySaleId(saleId: Long, now: Instant) = emptyList<io.github.sehako.japda.order.domain.model.InventoryReservation>()
		override fun save(reservation: io.github.sehako.japda.order.domain.model.InventoryReservation) = reservation.also { saved = it }
		override fun markPaymentPendingIfReservedAndNotExpired(orderId: Long, now: Instant) = false
		override fun transitionById(id: UUID, expectedStatus: InventoryReservationStatus, targetStatus: InventoryReservationStatus, updatedAt: Instant) = false
		override fun transitionByOrderId(orderId: Long, expectedStatus: InventoryReservationStatus, targetStatus: InventoryReservationStatus, updatedAt: Instant) = false
	}

	private class RecordingCounterRepository(
		private val committedQuantity: Long,
		private val saleQuantity: Int?,
	) : SaleInventoryCounterRepository {
		override fun create(saleId: Long, now: Instant) = Unit
		override fun reserve(saleId: Long, quantity: Int, now: Instant) =
			if (saleQuantity != null && saleQuantity.toLong() - committedQuantity >= quantity.toLong()) {
				SaleInventoryReserveResult.ACQUIRED
			} else {
				SaleInventoryReserveResult.INSUFFICIENT
			}
		override fun release(saleId: Long, quantity: Int, now: Instant) = true
		override fun findCommittedQuantity(saleId: Long): Int? = committedQuantity.toInt()
	}

	private class StubProductRepository(private val product: Product?) : ProductRepository {
		override fun save(product: Product) = product
		override fun findById(id: Long) = product
		override fun findByIdForUpdate(id: Long) = product
		override fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary> = emptyList()
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
		val IDEMPOTENCY_KEY: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
		val TOKEN = InventoryReservationToken(
			reservationId = "7f722e88-84e5-4ace-b25c-985b62304d65",
			saleId = 100L,
			generation = "e951261e-e793-48e7-ac4b-afd7e5bb7734",
			quantity = 2,
		)

		fun setId(target: Any, id: Long) {
			target::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(target, id)
			}
		}
	}
}
