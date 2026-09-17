package io.github.sehako.japda.order.application.inventory

import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("만료 재고 예약 반환 서비스")
class ExpiredInventoryReservationReleaseServiceTest {
	@Test
	@DisplayName("만료 RESERVED 전이에 성공한 예약 수량만 카운터에서 반환한다")
	fun 만료_RESERVED_전이_성공한_예약_수량만_카운터에서_반환한다() {
		val first = reservation(1L, 2)
		val competed = reservation(2L, 3)
		val reservationRepository = StubReservationRepository(listOf(first, competed), transitionedIds = setOf(first.id))
		val counterRepository = RecordingCounterRepository()

		ExpiredInventoryReservationReleaseService(reservationRepository, counterRepository).releaseExpired(SALE_ID, NOW)

		assertEquals(listOf(2), counterRepository.releasedQuantities)
	}

	@Test
	@DisplayName("예약 반환 뒤 카운터 감소가 실패하면 불변식 오류로 중단한다")
	fun 예약_반환_후_카운터_감소_실패_불변식_오류로_중단한다() {
		val expired = reservation(1L, 2)
		val exception = assertFailsWith<IllegalStateException> {
			ExpiredInventoryReservationReleaseService(
				StubReservationRepository(listOf(expired), transitionedIds = setOf(expired.id)),
				RecordingCounterRepository(releaseResult = false),
			).releaseExpired(SALE_ID, NOW)
		}

		assertEquals("재고 예약 반환 후 카운터 감소에 실패했습니다.", exception.message)
	}

	private fun reservation(orderId: Long, quantity: Int) = InventoryReservation.reserve(
		UUID.randomUUID(),
		SALE_ID,
		orderId,
		quantity,
		NOW,
		NOW.minusSeconds(180),
	)

	private class StubReservationRepository(
		private val expired: List<InventoryReservation>,
		private val transitionedIds: Set<UUID>,
	) : InventoryReservationRepository {
		override fun findByOrderId(orderId: Long): InventoryReservation? = expired.find { it.orderId == orderId }
		override fun findExpiredReservedBySaleId(saleId: Long, now: Instant) = expired
		override fun save(reservation: InventoryReservation) = reservation
		override fun markPaymentPendingIfReservedAndNotExpired(orderId: Long, now: Instant) = false
		override fun transitionById(
			id: UUID,
			expectedStatus: InventoryReservationStatus,
			targetStatus: InventoryReservationStatus,
			updatedAt: Instant,
		) = id in transitionedIds
		override fun transitionByOrderId(
			orderId: Long,
			expectedStatus: InventoryReservationStatus,
			targetStatus: InventoryReservationStatus,
			updatedAt: Instant,
		) = false
	}

	private class RecordingCounterRepository(
		private val releaseResult: Boolean = true,
	) : SaleInventoryCounterRepository {
		val releasedQuantities = mutableListOf<Int>()
		override fun create(saleId: Long, now: Instant) = Unit
		override fun reserve(saleId: Long, quantity: Int, now: Instant) = SaleInventoryReserveResult.ACQUIRED
		override fun release(saleId: Long, quantity: Int, now: Instant): Boolean {
			releasedQuantities += quantity
			return releaseResult
		}
		override fun findCommittedQuantity(saleId: Long): Int? = 0
	}

	private companion object {
		const val SALE_ID = 10L
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
	}
}
