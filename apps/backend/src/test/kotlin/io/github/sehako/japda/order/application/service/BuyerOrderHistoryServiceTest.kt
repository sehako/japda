package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.cursor.OrderCursorCodec
import io.github.sehako.japda.order.application.dto.ListBuyerOrdersDto
import io.github.sehako.japda.order.domain.model.OrderStatus
import io.github.sehako.japda.order.domain.repository.BuyerOrderCursorBoundary
import io.github.sehako.japda.order.domain.repository.BuyerOrderQuery
import io.github.sehako.japda.order.domain.repository.BuyerOrderQueryRepository
import io.github.sehako.japda.order.domain.repository.BuyerOrderSummary
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import tools.jackson.databind.json.JsonMapper

@DisplayName("구매자 주문 내역 서비스")
class BuyerOrderHistoryServiceTest {
	private val codec = OrderCursorCodec(JsonMapper.builder().build())

	@Test
	@DisplayName("페이지 크기보다 한 건 더 조회해 다음 커서를 생성한다")
	fun 다음_페이지_존재_마지막_응답_항목으로_커서를_생성한다() {
		val repository = RecordingBuyerOrderQueryRepository(
			listOf(summary(30L, "2026-09-11T06:00:00Z"), summary(20L, "2026-09-11T05:00:00Z"), summary(10L, "2026-09-11T04:00:00Z")),
		)
		val service = BuyerOrderHistoryService(repository, codec)

		val response = service.list(ListBuyerOrdersDto(123L, null, 2))

		assertEquals(BuyerOrderQuery(123L, null, 3), repository.query)
		assertEquals(listOf(30L, 20L), response.items.map { it.orderId })
		assertEquals(BuyerOrderCursorBoundary(Instant.parse("2026-09-11T05:00:00Z"), 20L), codec.decode(response.nextCursor!!))
	}

	@Test
	@DisplayName("마지막 페이지이면 다음 커서를 반환하지 않는다")
	fun 마지막_페이지_다음_커서를_반환하지_않는다() {
		val repository = RecordingBuyerOrderQueryRepository(listOf(summary(30L, "2026-09-11T06:00:00Z")))

		val response = BuyerOrderHistoryService(repository, codec).list(ListBuyerOrdersDto(123L, null, 2))

		assertNull(response.nextCursor)
	}

	@Test
	@DisplayName("요청 커서를 복원해 repository 경계로 전달한다")
	fun 요청_커서_복원_repository_경계로_전달한다() {
		val boundary = BuyerOrderCursorBoundary(Instant.parse("2026-09-11T05:00:00Z"), 20L)
		val repository = RecordingBuyerOrderQueryRepository(emptyList())

		BuyerOrderHistoryService(repository, codec).list(ListBuyerOrdersDto(123L, codec.encode(boundary), 20))

		assertEquals(BuyerOrderQuery(123L, boundary, 21), repository.query)
	}

	@Test
	@DisplayName("페이지 크기가 1에서 100 범위를 벗어나면 거부한다")
	fun 페이지_크기_범위_밖_거부한다() {
		listOf(0, 101).forEach { size ->
			val exception = assertFailsWith<OrderException> {
				BuyerOrderHistoryService(RecordingBuyerOrderQueryRepository(emptyList()), codec)
					.list(ListBuyerOrdersDto(123L, null, size))
			}
			assertEquals(OrderErrorCode.PAGE_SIZE_INVALID, exception.errorCode)
		}
	}

	private fun summary(orderId: Long, createdAt: String) = BuyerOrderSummary(
		orderId = orderId,
		status = OrderStatus.PENDING_PAYMENT,
		productName = "한정판 상품",
		quantity = 2,
		unitPrice = 35_000L,
		totalPrice = 70_000L,
		createdAt = Instant.parse(createdAt),
		expiresAt = Instant.parse(createdAt).plusSeconds(180),
	)

	private class RecordingBuyerOrderQueryRepository(
		private val result: List<BuyerOrderSummary>,
	) : BuyerOrderQueryRepository {
		var query: BuyerOrderQuery? = null

		override fun findAll(query: BuyerOrderQuery): List<BuyerOrderSummary> {
			this.query = query
			return result
		}
	}
}
