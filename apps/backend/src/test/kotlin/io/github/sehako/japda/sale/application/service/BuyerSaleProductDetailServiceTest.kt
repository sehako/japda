package io.github.sehako.japda.sale.application.service

import io.github.sehako.japda.sale.application.response.BuyerSaleProductDetailImageResponse
import io.github.sehako.japda.sale.application.response.BuyerSaleStatus
import io.github.sehako.japda.sale.domain.repository.BuyerSaleProductDetailImageQueryResult
import io.github.sehako.japda.sale.domain.repository.BuyerSaleProductDetailQueryResult
import io.github.sehako.japda.sale.domain.repository.BuyerSaleProductQueryRepository
import io.github.sehako.japda.sale.domain.repository.BuyerSaleProductQueryResult
import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("구매자 판매 상품 상세 서비스")
class BuyerSaleProductDetailServiceTest {
	@Test
	@DisplayName("양수가 아닌 판매 일정 식별자는 조회하지 않고 거절한다")
	fun 양수가_아닌_판매_일정_식별자_조회하지_않고_거절한다() {
		val repository = RecordingQueryRepository(detail = detailResult())
		val service = service(repository, FixedCountingClock("2026-09-10T03:00:00Z"))

		listOf(0L, -1L).forEach { saleId ->
			val exception = assertFailsWith<SaleException> {
				service.findBuyerSaleProductDetail(saleId)
			}

			assertEquals(SaleErrorCode.ID_INVALID, exception.errorCode)
		}
		assertEquals(emptyList(), repository.requestedSaleIds)
	}

	@Test
	@DisplayName("판매 일정이 존재하지 않으면 찾을 수 없음으로 거절한다")
	fun 판매_일정이_존재하지_않음_찾을_수_없음으로_거절한다() {
		val repository = RecordingQueryRepository(detail = null)
		val service = service(repository, FixedCountingClock("2026-09-10T03:00:00Z"))

		val exception = assertFailsWith<SaleException> {
			service.findBuyerSaleProductDetail(999L)
		}

		assertEquals(SaleErrorCode.NOT_FOUND, exception.errorCode)
		assertEquals(listOf(999L), repository.requestedSaleIds)
	}

	@Test
	@DisplayName("판매 시작과 종료 경계에 맞는 상태로 상세 정보를 반환한다")
	fun 판매_시작과_종료_경계에_맞는_상태로_상세_정보를_반환한다() {
		val cases = listOf(
			"2026-09-09T14:59:59Z" to BuyerSaleStatus.UPCOMING,
			"2026-09-09T15:00:00Z" to BuyerSaleStatus.ON_SALE,
			"2026-09-10T14:59:59Z" to BuyerSaleStatus.ON_SALE,
			"2026-09-10T15:00:00Z" to BuyerSaleStatus.ENDED,
		)

		cases.forEach { (now, expectedStatus) ->
			val response = service(RecordingQueryRepository(detailResult()), FixedCountingClock(now))
				.findBuyerSaleProductDetail(100L)

			assertEquals(expectedStatus, response.status)
			assertEquals(Instant.parse("2026-09-09T15:00:00Z"), response.startsAt)
			assertEquals(Instant.parse("2026-09-10T15:00:00Z"), response.endsAt)
		}
	}

	@Test
	@DisplayName("현재 시각을 한 번 읽고 이미지 순서와 대표 여부를 보존해 상대 경로로 변환한다")
	fun 현재_시각을_한번_읽고_이미지_순서와_대표_여부를_보존해_상대_경로로_변환한다() {
		val clock = FixedCountingClock("2026-09-10T03:00:00Z")
		val response = service(RecordingQueryRepository(detailResult()), clock)
			.findBuyerSaleProductDetail(100L)

		assertEquals(1, clock.instantCallCount)
		assertEquals(100L, response.saleId)
		assertEquals(42L, response.productId)
		assertEquals("한정판 상품", response.name)
		assertEquals(null, response.description)
		assertEquals(35_000L, response.price)
		assertEquals(100, response.quantity)
		assertEquals(LocalDate.parse("2026-09-10"), response.saleDate)
		assertEquals(
			listOf(
				BuyerSaleProductDetailImageResponse("/products/42/request/image-a", 0, true),
				BuyerSaleProductDetailImageResponse("/products/42/request/image-b", 1, false),
			),
			response.images,
		)
	}

	private fun service(repository: BuyerSaleProductQueryRepository, clock: Clock): SaleService = SaleService(
		buyerSaleProductQueryRepository = repository,
		clock = clock,
	)

	private fun detailResult() = BuyerSaleProductDetailQueryResult(
		saleId = 100L,
		productId = 42L,
		name = "한정판 상품",
		description = null,
		price = 35_000L,
		quantity = 100,
		saleDate = LocalDate.parse("2026-09-10"),
		images = listOf(
			BuyerSaleProductDetailImageQueryResult("products/42/request/image-a", 0, true),
			BuyerSaleProductDetailImageQueryResult("products/42/request/image-b", 1, false),
		),
	)

	private class RecordingQueryRepository(
		private val detail: BuyerSaleProductDetailQueryResult?,
	) : BuyerSaleProductQueryRepository {
		val requestedSaleIds = mutableListOf<Long>()

		override fun findAllBySaleDate(saleDate: LocalDate): List<BuyerSaleProductQueryResult> = emptyList()

		override fun findDetailBySaleId(saleId: Long): BuyerSaleProductDetailQueryResult? {
			requestedSaleIds += saleId
			return detail
		}
	}

	private class FixedCountingClock(now: String) : Clock() {
		private val fixedInstant = Instant.parse(now)
		var instantCallCount = 0
			private set

		override fun getZone(): ZoneId = ZoneOffset.UTC

		override fun withZone(zone: ZoneId): Clock = this

		override fun instant(): Instant {
			instantCallCount++
			return fixedInstant
		}
	}
}
