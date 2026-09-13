package io.github.sehako.japda.payment.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.DisplayName

@DisplayName("결제 시도")
class PaymentTest {
	@Test
	@DisplayName("새 시도는 서버 멱등키와 재확인 시각을 저장한다")
	fun 새_시도_멱등키와_재확인_시각을_저장한다() {
		val first = Payment.create(1L, "payment-key", 70_000L, NOW)
		val second = Payment.create(2L, "another-key", 70_000L, NOW)

		assertEquals(PaymentStatus.CONFIRMING, first.status)
		assertEquals(NOW.plusSeconds(30), first.nextReconcileAt)
		assertNotEquals(first.tossIdempotencyKey, second.tossIdempotencyKey)
	}

	@Test
	@DisplayName("다른 결제 키로 기존 시도를 재사용할 수 없다")
	fun 다른_결제_키_기존_시도를_재사용할_수_없다() {
		val payment = Payment.create(1L, "payment-key", 70_000L, NOW)

		assertFailsWith<IllegalArgumentException> { payment.requireSameKey("other-key") }
	}

	@Test
	@DisplayName("승인하면 확정 시각을 저장하고 실패로 되돌릴 수 없다")
	fun 승인_확정_시각을_저장하고_실패로_되돌릴_수_없다() {
		val payment = Payment.create(1L, "payment-key", 70_000L, NOW)
		val approvedAt = NOW.plusSeconds(2)

		payment.approve(approvedAt)

		assertEquals(PaymentStatus.APPROVED, payment.status)
		assertEquals(approvedAt, payment.approvedAt)
		assertFailsWith<IllegalStateException> { payment.fail(NOW.plusSeconds(3)) }
	}

	private companion object {
		val NOW = Instant.parse("2026-09-13T06:00:00Z")
	}
}
