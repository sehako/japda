package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.domain.model.OrderRequest
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
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName

@DisplayName("주문 서비스")
class OrderServiceTest {
	@Test
	@DisplayName("판매 중인 상품을 주문하면 서버 원본으로 주문을 저장한다")
	fun 판매_중인_상품_주문_서버_원본으로_주문을_저장한다() {
		val orderRepository = RecordingOrderRepository(reservedQuantity = 3)
		val saleRepository = StubSaleRepository(sale(quantity = 10, price = 35_000L))
		val service = service(orderRepository, saleRepository)

		val response = service.create(dto(quantity = 2))

		assertEquals("서버 상품명", response.productName)
		assertEquals(35_000L, response.unitPrice)
		assertEquals(70_000L, response.totalPrice)
		assertEquals(NOW, orderRepository.aggregatedAt)
		assertEquals(NOW, orderRepository.savedOrder?.createdAt)
		assertEquals(1, saleRepository.lockCount)
	}

	@Test
	@DisplayName("같은 멱등성 키와 동일 요청이면 판매 잠금 없이 기존 주문을 반환한다")
	fun 같은_멱등성_키_동일_요청_판매_잠금_없이_기존_주문을_반환한다() {
		val request = dto().toDomainRequest()
		val existing = Order.create(request, "서버 상품명", 35_000L, NOW).also { setId(it, 9L) }
		val orderRepository = RecordingOrderRepository(existing = existing)
		val saleRepository = StubSaleRepository(sale())

		val response = service(orderRepository, saleRepository).create(dto())

		assertEquals(9L, response.orderId)
		assertEquals(existing.paymentOrderId, response.paymentOrderId)
		assertEquals(NOW.plusSeconds(180), response.expiresAt)
		assertEquals(0, saleRepository.lockCount)
		assertNull(orderRepository.savedOrder)
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
	): OrderService {
		val transactionService = OrderCreationTransactionService(
			orderRepository,
			saleRepository,
			StubProductRepository(product()),
			Clock.fixed(NOW, ZoneOffset.UTC),
		)
		return OrderService(orderRepository, transactionService)
	}

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
		private val existing: Order? = null,
		private val reservedQuantity: Long = 0,
	) : OrderRepository {
		var savedOrder: Order? = null
		var aggregatedAt: Instant? = null

		override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order? = existing

		override fun findByPaymentOrderId(paymentOrderId: String): Order? = existing?.takeIf { it.paymentOrderId == paymentOrderId }

		override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? =
			existing?.takeIf { it.paymentOrderId == paymentOrderId && it.buyerId == buyerId }?.saleId

		override fun findById(id: Long): Order? = existing?.takeIf { it.id == id }

		override fun findSaleIdById(id: Long): Long? = existing?.takeIf { it.id == id }?.saleId

		override fun sumCommittedQuantity(saleId: Long, now: Instant): Long {
			aggregatedAt = now
			return reservedQuantity
		}

		override fun save(order: Order): Order = order.also {
			setId(it, 1L)
			savedOrder = it
		}
	}

	private class StubSaleRepository(private val sale: Sale?) : SaleRepository {
		var lockCount = 0
		override fun save(sale: Sale): Sale = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate) = false
		override fun findByIdForUpdate(id: Long): Sale? = sale.also { lockCount++ }
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

		fun setId(target: Any, id: Long) {
			target::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(target, id)
			}
		}
	}
}
