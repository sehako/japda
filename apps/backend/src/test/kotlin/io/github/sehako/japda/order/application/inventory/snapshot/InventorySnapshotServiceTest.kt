package io.github.sehako.japda.order.application.inventory.snapshot

import io.github.sehako.japda.order.domain.model.Order
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
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource

@DisplayName("재고 snapshot 서비스")
class InventorySnapshotServiceTest {
	@Test
	@DisplayName("snapshot 조회는 REPEATABLE_READ 읽기 전용 transaction을 2초로 제한한다")
	fun snapshot_조회_REPEATABLE_READ_읽기_전용_transaction_2초로_제한한다() {
		val method = InventorySnapshotService::class.java.getMethod("read", Long::class.javaPrimitiveType)
		val attribute = checkNotNull(
			AnnotationTransactionAttributeSource().getTransactionAttribute(method, InventorySnapshotService::class.java),
		)

		assertTrue(attribute.isReadOnly)
		assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, attribute.isolationLevel)
		assertEquals(2, attribute.timeout)
	}

	@Test
	@DisplayName("판매 수량에서 유효한 예약 수량을 뺀 가용 수량을 반환한다")
	fun 판매_수량에서_유효한_예약_수량을_뺀_가용_수량을_반환한다() {
		val orderRepository = StubOrderRepository(committedQuantity = 4L)
		val service = InventorySnapshotService(
			orderRepository,
			StubSaleRepository(quantity = 10),
			Clock.fixed(NOW, ZoneOffset.UTC),
		)

		val result = service.read(100L)

		assertEquals(InventorySnapshotResult.Available(6), result)
		assertEquals(100L, orderRepository.aggregatedSaleId)
		assertEquals(NOW, orderRepository.aggregatedAt)
	}

	@Test
	@DisplayName("판매 일정이 없으면 초기화 실패를 반환한다")
	fun 판매_일정이_없으면_초기화_실패를_반환한다() {
		val result = InventorySnapshotService(
			StubOrderRepository(committedQuantity = 0L),
			StubSaleRepository(quantity = null),
			Clock.fixed(NOW, ZoneOffset.UTC),
		).read(100L)

		assertEquals(InventorySnapshotResult.InitializationFailed, result)
	}

	@Test
	@DisplayName("예약 수량이 판매 수량보다 많으면 초기화 실패를 반환한다")
	fun 음수_가용_수량_초기화_실패를_반환한다() {
		val result = InventorySnapshotService(
			StubOrderRepository(committedQuantity = 11L),
			StubSaleRepository(quantity = 10),
			Clock.fixed(NOW, ZoneOffset.UTC),
		).read(100L)

		assertEquals(InventorySnapshotResult.InitializationFailed, result)
	}

	@Test
	@DisplayName("계산 결과가 Int 최댓값을 넘으면 초기화 실패를 반환한다")
	fun Int_최댓값_초과_초기화_실패를_반환한다() {
		val result = InventorySnapshotService(
			StubOrderRepository(committedQuantity = -1L),
			StubSaleRepository(quantity = Int.MAX_VALUE),
			Clock.fixed(NOW, ZoneOffset.UTC),
		).read(100L)

		assertEquals(InventorySnapshotResult.InitializationFailed, result)
	}

	private class StubOrderRepository(
		private val committedQuantity: Long,
	) : OrderRepository {
		var aggregatedSaleId: Long? = null
		var aggregatedAt: Instant? = null

		override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order? = null
		override fun findByPaymentOrderId(paymentOrderId: String): Order? = null
		override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? = null
		override fun findById(id: Long): Order? = null
		override fun findSaleIdById(id: Long): Long? = null
		override fun sumCommittedQuantity(saleId: Long, now: Instant): Long {
			aggregatedSaleId = saleId
			aggregatedAt = now
			return committedQuantity
		}
		override fun save(order: Order): Order = order
	}

	private class StubSaleRepository(
		private val quantity: Int?,
	) : SaleRepository {
		override fun save(sale: Sale): Sale = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean = false
		override fun findByIdForUpdate(id: Long): Sale? = null
		override fun findQuantityById(id: Long): Int? = quantity
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
	}
}
