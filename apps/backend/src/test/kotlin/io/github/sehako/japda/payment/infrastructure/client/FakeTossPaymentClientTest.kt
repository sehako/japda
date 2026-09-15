package io.github.sehako.japda.payment.infrastructure.client

import io.github.sehako.japda.payment.application.client.TossPaymentResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

@DisplayName("Fake 토스 결제 Client")
class FakeTossPaymentClientTest {
	@Test
	@DisplayName("승인 요청은 입력값과 현재 시각으로 완료 결과를 반환한다")
	fun 승인_요청_입력값과_현재_시각으로_완료_결과를_반환한다() {
		val now = Instant.parse("2026-09-15T03:00:00Z")
		val client = FakeTossPaymentClient(Duration.ZERO, Clock.fixed(now, ZoneOffset.UTC))

		val result = assertIs<TossPaymentResult.Record>(client.confirm("payment-key", "order-123", 70_000L, "uuid-1"))

		assertEquals("payment-key", result.paymentKey)
		assertEquals("order-123", result.orderId)
		assertEquals(70_000L, result.totalAmount)
		assertEquals("DONE", result.status)
		assertEquals(now, result.approvedAt)
	}

	@Test
	@DisplayName("승인 요청은 설정된 응답 지연만큼 대기한다")
	fun 승인_요청_설정된_응답_지연만큼_대기한다() {
		val client = FakeTossPaymentClient(Duration.ofMillis(80), Clock.systemUTC())

		val elapsed = measureTimeMillis {
			client.confirm("payment-key", "order-123", 70_000L, "uuid-1")
		}

		assertTrue(elapsed >= 70, "실제 대기 시간: ${elapsed}ms")
	}

	@Test
	@DisplayName("승인 대기 중 interrupt가 발생하면 상태를 복원하고 일시적 실패를 반환한다")
	fun 승인_대기_중_interrupt_상태를_복원하고_일시적_실패를_반환한다() {
		val client = FakeTossPaymentClient(Duration.ofSeconds(1), Clock.systemUTC())
		Thread.currentThread().interrupt()

		try {
			assertEquals(TossPaymentResult.Unavailable, client.confirm("payment-key", "order-123", 70_000L, "uuid-1"))
			assertTrue(Thread.currentThread().isInterrupted)
		} finally {
			Thread.interrupted()
		}
	}

	@Test
	@DisplayName("결제 조회는 미발견 결과를 반환한다")
	fun 결제_조회_미발견_결과를_반환한다() {
		val client = FakeTossPaymentClient(Duration.ZERO, Clock.systemUTC())

		assertEquals(TossPaymentResult.NotFound, client.lookup("payment-key"))
	}
}
