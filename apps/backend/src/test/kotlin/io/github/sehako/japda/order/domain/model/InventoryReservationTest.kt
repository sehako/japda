package io.github.sehako.japda.order.domain.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("재고 예약")
class InventoryReservationTest {
	@Test
	@DisplayName("주문 정보로 예약을 생성하면 RESERVED 상태와 시각을 저장한다")
	fun 주문_정보로_예약_생성_RESERVED_상태와_시각을_저장한다() {
		val reservation = InventoryReservation.reserve(RESERVATION_ID, 10L, 20L, 3, EXPIRES_AT, NOW)

		assertEquals(RESERVATION_ID, reservation.id)
		assertEquals(10L, reservation.saleId)
		assertEquals(20L, reservation.orderId)
		assertEquals(3, reservation.quantity)
		assertEquals(InventoryReservationStatus.RESERVED, reservation.status)
		assertEquals(EXPIRES_AT, reservation.expiresAt)
		assertEquals(NOW, reservation.createdAt)
		assertEquals(NOW, reservation.updatedAt)
	}

	@Test
	@DisplayName("양수가 아닌 예약 수량은 거절한다")
	fun 양수가_아닌_예약_수량_거절한다() {
		assertFailsWith<IllegalArgumentException> {
			InventoryReservation.reserve(RESERVATION_ID, 10L, 20L, 0, EXPIRES_AT, NOW)
		}
	}

	private companion object {
		val RESERVATION_ID: UUID = UUID.fromString("7f722e88-84e5-4ace-b25c-985b62304d65")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
		val EXPIRES_AT: Instant = NOW.plusSeconds(180)
	}
}
