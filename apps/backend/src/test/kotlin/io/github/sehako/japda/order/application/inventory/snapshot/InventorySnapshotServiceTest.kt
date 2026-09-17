package io.github.sehako.japda.order.application.inventory.snapshot

import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.DisplayName
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource

@DisplayName("재고 snapshot 서비스")
class InventorySnapshotServiceTest {
	@Test
	@DisplayName("snapshot 조회는 REPEATABLE_READ 쓰기 transaction을 2초로 제한한다")
	fun snapshot_조회_REPEATABLE_READ_쓰기_transaction_2초로_제한한다() {
		val method = InventorySnapshotService::class.java.getMethod("read", Long::class.javaPrimitiveType)
		val attribute = checkNotNull(
			AnnotationTransactionAttributeSource().getTransactionAttribute(method, InventorySnapshotService::class.java),
		)

		assertFalse(attribute.isReadOnly)
		assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, attribute.isolationLevel)
		assertEquals(2, attribute.timeout)
	}

	@Test
	@DisplayName("만료 예약을 반환한 뒤 판매 수량에서 카운터 수량을 뺀 가용 수량을 반환한다")
	fun 만료_예약_반환_후_판매_수량에서_카운터_수량을_뺀_가용_수량을_반환한다() {
		val counterRepository = StubCounterRepository(committedQuantity = 4)
		val reservationRepository = RecordingReservationRepository()
		val service = InventorySnapshotService(
			counterRepository,
			StubSaleRepository(quantity = 10),
			ExpiredInventoryReservationReleaseService(reservationRepository, counterRepository),
			Clock.fixed(NOW, ZoneOffset.UTC),
		)

		val result = service.read(100L)

		assertEquals(InventorySnapshotResult.Available(6), result)
		assertEquals(100L, reservationRepository.expiredSaleId)
		assertEquals(NOW, reservationRepository.expiredAt)
	}

	@Test
	@DisplayName("판매 일정이 없으면 초기화 실패를 반환한다")
	fun 판매_일정_없음_초기화_실패를_반환한다() {
		assertEquals(InventorySnapshotResult.InitializationFailed, service(0, null).read(100L))
	}

	@Test
	@DisplayName("재고 카운터가 없으면 초기화 실패를 반환한다")
	fun 재고_카운터_없음_초기화_실패를_반환한다() {
		assertEquals(InventorySnapshotResult.InitializationFailed, service(null, 10).read(100L))
	}

	@Test
	@DisplayName("카운터 수량이 판매 수량보다 많으면 초기화 실패를 반환한다")
	fun 카운터_수량_판매_수량_초과_초기화_실패를_반환한다() {
		assertEquals(InventorySnapshotResult.InitializationFailed, service(11, 10).read(100L))
	}

	private fun service(committedQuantity: Int?, saleQuantity: Int?): InventorySnapshotService {
		val counterRepository = StubCounterRepository(committedQuantity)
		val reservationRepository = RecordingReservationRepository()
		return InventorySnapshotService(
			counterRepository,
			StubSaleRepository(saleQuantity),
			ExpiredInventoryReservationReleaseService(reservationRepository, counterRepository),
			Clock.fixed(NOW, ZoneOffset.UTC),
		)
	}

	private class RecordingReservationRepository : InventoryReservationRepository {
		var expiredSaleId: Long? = null
		var expiredAt: Instant? = null
		override fun findByOrderId(orderId: Long): InventoryReservation? = null
		override fun findExpiredReservedBySaleId(saleId: Long, now: Instant): List<InventoryReservation> {
			expiredSaleId = saleId
			expiredAt = now
			return emptyList()
		}
		override fun save(reservation: InventoryReservation) = reservation
		override fun markPaymentPendingIfReservedAndNotExpired(orderId: Long, now: Instant) = false
		override fun transitionById(id: UUID, expectedStatus: InventoryReservationStatus, targetStatus: InventoryReservationStatus, updatedAt: Instant) = false
		override fun transitionByOrderId(orderId: Long, expectedStatus: InventoryReservationStatus, targetStatus: InventoryReservationStatus, updatedAt: Instant) = false
	}

	private class StubCounterRepository(private val committedQuantity: Int?) : SaleInventoryCounterRepository {
		override fun create(saleId: Long, now: Instant) = Unit
		override fun reserve(saleId: Long, quantity: Int, now: Instant) = SaleInventoryReserveResult.ACQUIRED
		override fun release(saleId: Long, quantity: Int, now: Instant) = true
		override fun findCommittedQuantity(saleId: Long): Int? = committedQuantity
	}

	private class StubSaleRepository(private val quantity: Int?) : SaleRepository {
		override fun save(sale: Sale): Sale = sale
		override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate) = false
		override fun findByIdForUpdate(id: Long): Sale? = null
		override fun findQuantityById(id: Long): Int? = quantity
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
	}
}
