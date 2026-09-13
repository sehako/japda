package io.github.sehako.japda.order.domain.model

import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

@DisplayName("주문")
class OrderTest {
	@Test
	@DisplayName("주문 요청을 만들면 배송 문자열을 정규화한다")
	fun 주문_요청_생성_배송_문자열을_정규화한다() {
		val request = request(deliveryMessage = "   ")

		assertEquals("홍길동", request.shippingAddress.recipientName)
		assertNull(request.shippingAddress.deliveryMessage)
	}

	@Test
	@DisplayName("배송 필수값이 공백이면 해당 필드 오류로 거절한다")
	fun 배송_필수값_공백_해당_필드_오류로_거절한다() {
		val exception = assertFailsWith<OrderException> {
			request(recipientName = "  ")
		}

		assertEquals(OrderErrorCode.RECIPIENT_NAME_INVALID, exception.errorCode)
	}

	@Test
	@DisplayName("주문을 생성하면 서버 가격으로 총액과 3분 예약을 만든다")
	fun 주문_생성_서버_가격으로_총액과_3분_예약을_만든다() {
		val order = Order.create(request(), "한정판 상품", 35_000L, CREATED_AT)

		assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
		assertEquals(70_000L, order.totalPrice)
		assertEquals(Duration.ofMinutes(3), Duration.between(order.createdAt, order.expiresAt))
	}

	@Test
	@DisplayName("주문을 생성하면 토스페이먼츠 규격의 UUID v4 결제 주문 식별자를 만든다")
	fun 주문_생성_토스페이먼츠_규격_UUID_v4_결제_주문_식별자를_만든다() {
		val order = Order.create(request(), "한정판 상품", 35_000L, CREATED_AT)

		val paymentOrderId = order.paymentOrderId
		val uuid = UUID.fromString(paymentOrderId)
		assertEquals(paymentOrderId, uuid.toString())
		assertEquals(4, uuid.version())
		assertTrue(paymentOrderId.matches(Regex("^[A-Za-z0-9_=-]{6,64}$")))
	}

	@Test
	@DisplayName("서로 다른 주문을 생성하면 서로 다른 결제 주문 식별자를 만든다")
	fun 서로_다른_주문_생성_서로_다른_결제_주문_식별자를_만든다() {
		val first = Order.create(request(), "한정판 상품", 35_000L, CREATED_AT)
		val second = Order.create(request(), "한정판 상품", 35_000L, CREATED_AT)

		assertNotEquals(first.paymentOrderId, second.paymentOrderId)
	}

	@Test
	@DisplayName("총액이 Long 범위를 넘으면 주문 생성을 거절한다")
	fun 총액_Long_범위_초과_주문_생성을_거절한다() {
		val exception = assertFailsWith<OrderException> {
			Order.create(request(quantity = 2), "상품", Long.MAX_VALUE, CREATED_AT)
		}

		assertEquals(OrderErrorCode.TOTAL_PRICE_INVALID, exception.errorCode)
	}

	@Test
	@DisplayName("결제 완료 시 주문 상태를 변경하고 원래 만료 시각은 유지한다")
	fun 결제_완료_주문_상태를_변경하고_만료_시각을_유지한다() {
		val order = Order.create(request(), "상품", 35_000L, CREATED_AT)
		val expiresAt = order.expiresAt

		order.markPaid()

		assertEquals(OrderStatus.PAID, order.status)
		assertEquals(expiresAt, order.expiresAt)
	}

	@Test
	@DisplayName("저장된 주문은 정규화 값이 같은 재요청만 동일하다고 판단한다")
	fun 저장된_주문_정규화_값이_같은_재요청만_동일하다고_판단한다() {
		val request = request(deliveryMessage = " 문 앞 ")
		val order = Order.create(request, "상품", 1_000L, CREATED_AT)

		assertTrue(order.matches(request(deliveryMessage = "문 앞")))
	}

	private fun request(
		quantity: Int? = 2,
		recipientName: String? = " 홍길동 ",
		deliveryMessage: String? = null,
	): OrderRequest = OrderRequest.create(
		buyerId = 123L,
		idempotencyKey = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
		saleId = 100L,
		quantity = quantity,
		recipientName = recipientName,
		phoneNumber = " 010-1234-5678 ",
		postalCode = " 06236 ",
		address = " 서울특별시 강남구 테헤란로 123 ",
		detailAddress = " 101동 1001호 ",
		deliveryMessage = deliveryMessage,
	)

	private companion object {
		val CREATED_AT: Instant = Instant.parse("2026-09-11T06:00:00Z")
	}
}
