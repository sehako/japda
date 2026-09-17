package io.github.sehako.japda.order.application.cursor

import io.github.sehako.japda.order.domain.repository.BuyerOrderCursorBoundary
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.DisplayName
import tools.jackson.databind.json.JsonMapper

@DisplayName("주문 목록 커서 코덱")
class OrderCursorCodecTest {
	private val codec = OrderCursorCodec(JsonMapper.builder().build())

	@Test
	@DisplayName("생성 시각과 주문 식별자를 URL-safe Base64 커서로 인코딩하고 복원한다")
	fun 생성_시각과_주문_식별자_커서로_인코딩하고_복원한다() {
		val boundary = BuyerOrderCursorBoundary(Instant.parse("2026-09-11T06:00:00.123456Z"), 37L)

		val encoded = codec.encode(boundary)

		assertFalse(encoded.contains('='))
		assertEquals(boundary, codec.decode(encoded))
	}

	@Test
	@DisplayName("손상되거나 지원하지 않는 버전의 커서를 거부한다")
	fun 잘못된_커서_거부한다() {
		listOf(
			"%%%",
			payload("""{"version":2,"createdAt":"2026-09-11T06:00:00Z","orderId":1}"""),
			payload("""{"version":1,"createdAt":"not-an-instant","orderId":1}"""),
			payload("""{"version":1,"createdAt":"2026-09-11T06:00:00Z","orderId":0}"""),
			payload("""{"version":1,"createdAt":"2026-09-11T06:00:00Z","orderId":1.2}"""),
		).forEach { cursor ->
			val exception = assertFailsWith<OrderException> { codec.decode(cursor) }
			assertEquals(OrderErrorCode.CURSOR_INVALID, exception.errorCode)
		}
	}

	private fun payload(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}
