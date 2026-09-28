package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.sale.domain.repository.SaleCommittedQuantityIncreaseResult
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
import org.junit.jupiter.api.DisplayName

@DisplayName("주문 생성 transaction 서비스")
class OrderCreationTransactionServiceTest {
	@Test
	@DisplayName("조건부 판매 수량 증가에 성공하면 주문을 저장한다")
	fun 조건부_판매_수량_증가_성공_주문을_저장한다() {
		val saleRepository = RecordingSaleRepository(SaleCommittedQuantityIncreaseResult.Increased)

		val result = service(saleRepository).create(request())

		assertEquals(true, result.created)
		assertEquals(listOf(2), saleRepository.increasedQuantities)
	}

	@Test
	@DisplayName("조건부 판매 수량 증가가 재고 부족이면 실제 잔여 수량을 내부 실패로 전달한다")
	fun 조건부_판매_수량_증가_재고_부족_실제_잔여_수량을_내부_실패로_전달한다() {
		val exception = assertFailsWith<OrderInventoryInsufficientException> {
			service(
				RecordingSaleRepository(SaleCommittedQuantityIncreaseResult.Insufficient(3)),
			).create(request())
		}

		assertEquals(3, exception.remainingQuantity)
	}

	@Test
	@DisplayName("판매 일정이 없으면 재고 증가 결과를 찾을 수 없음으로 중단한다")
	fun 판매_일정_존재하지_않음_불변식_오류로_중단한다() {
		val exception = assertFailsWith<IllegalStateException> {
			service(
				RecordingSaleRepository(SaleCommittedQuantityIncreaseResult.MissingSale),
			).create(request())
		}

		assertEquals("판매 일정의 재고 수량을 찾을 수 없습니다.", exception.message)
	}

	private fun service(
		saleRepository: RecordingSaleRepository,
	): OrderCreationTransactionService {
		val orderRepository = RecordingOrderRepository()
		return OrderCreationTransactionService(
			orderRepository,
			saleRepository,
			StubProductRepository(product()),
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

	private class RecordingSaleRepository(
		private val result: SaleCommittedQuantityIncreaseResult,
	) : SaleRepository {
		val increasedQuantities = mutableListOf<Int>()
		private val savedSale = Sale.create(10L, 1L, LocalDate.parse("2026-09-11"), 35_000L, 10, NOW)
		override fun save(sale: Sale) = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate) = false
		override fun increaseCommittedQuantity(saleId: Long, quantity: Int) = result.also { increasedQuantities += quantity }
		override fun decreaseCommittedQuantity(saleId: Long, quantity: Int) = true
		override fun findById(id: Long): Sale? = savedSale
		override fun findByIdForUpdate(id: Long): Sale? = null
	}

	private class StubProductRepository(private val product: Product) : ProductRepository {
		override fun save(product: Product) = product
		override fun findById(id: Long): Product? = product
		override fun findByIdForUpdate(id: Long): Product? = product
		override fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary> = emptyList()
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
