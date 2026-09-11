package io.github.sehako.japda.sale.application

import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryRepository
import io.github.sehako.japda.sale.domain.BuyerSaleProductDetailQueryResult
import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryResult
import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("구매자 판매 상품 목록 서비스")
class BuyerSaleProductServiceTest {
    @Test
    @DisplayName("과거, 오늘과 내일은 조회한다")
    fun 과거_오늘_내일_조회한다() {
        val repository = RecordingQueryRepository(emptyList())
        val service = service(repository, "2026-09-10T03:00:00Z")

        listOf("2020-01-01", "2026-09-10", "2026-09-11").forEach {
            assertEquals(emptyList(), service.findBuyerSaleProducts(LocalDate.parse(it)).sales)
        }

        assertEquals(
            listOf(LocalDate.parse("2020-01-01"), LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-11")),
            repository.requestedDates,
        )
    }

    @Test
    @DisplayName("한국 기준 모레부터 조회를 거절한다")
    fun 한국_기준_모레부터_조회를_거절한다() {
        val service = service(RecordingQueryRepository(emptyList()), "2026-09-10T14:59:59Z")

        val exception = assertFailsWith<SaleException> {
            service.findBuyerSaleProducts(LocalDate.parse("2026-09-12"))
        }

        assertEquals(SaleErrorCode.DATE_OUT_OF_RANGE, exception.errorCode)
    }

    @Test
    @DisplayName("한국 기준 자정에 조회 상한이 하루 늘어난다")
    fun 한국_기준_자정에_조회_상한이_하루_늘어난다() {
        val requestedDate = LocalDate.parse("2026-09-12")

        assertFailsWith<SaleException> {
            service(RecordingQueryRepository(emptyList()), "2026-09-10T14:59:59Z").findBuyerSaleProducts(requestedDate)
        }
        assertEquals(
            emptyList(),
            service(RecordingQueryRepository(emptyList()), "2026-09-10T15:00:00Z").findBuyerSaleProducts(requestedDate).sales,
        )
    }

    @Test
    @DisplayName("판매 시간 경계와 대표 이미지 상대 경로를 응답으로 변환한다")
    fun 판매_시간_경계와_대표_이미지_상대_경로를_응답으로_변환한다() {
        val saleDate = LocalDate.parse("2026-09-10")
        val result = BuyerSaleProductQueryResult(
            saleId = 100L,
            productId = 42L,
            name = "한정판 상품",
            description = null,
            price = 35_000L,
            quantity = 100,
            saleDate = saleDate,
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
            representativeImageObjectKey = "products/42/request/image",
        )

        val upcoming = service(RecordingQueryRepository(listOf(result)), "2026-09-09T14:59:59Z")
            .findBuyerSaleProducts(saleDate).sales.single()
        val onSale = service(RecordingQueryRepository(listOf(result)), "2026-09-09T15:00:00Z")
            .findBuyerSaleProducts(saleDate).sales.single()
        val ended = service(RecordingQueryRepository(listOf(result)), "2026-09-10T15:00:00Z")
            .findBuyerSaleProducts(saleDate).sales.single()

        assertEquals(BuyerSaleStatus.UPCOMING, upcoming.status)
        assertEquals(BuyerSaleStatus.ON_SALE, onSale.status)
        assertEquals(BuyerSaleStatus.ENDED, ended.status)
        assertEquals(Instant.parse("2026-09-09T15:00:00Z"), onSale.startsAt)
        assertEquals(Instant.parse("2026-09-10T15:00:00Z"), onSale.endsAt)
        assertEquals("/products/42/request/image", onSale.representativeImagePath)
    }

    private fun service(repository: BuyerSaleProductQueryRepository, now: String): SaleService = SaleService(
        buyerSaleProductQueryRepository = repository,
        clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
    )

    private class RecordingQueryRepository(
        private val results: List<BuyerSaleProductQueryResult>,
    ) : BuyerSaleProductQueryRepository {
        val requestedDates = mutableListOf<LocalDate>()

        override fun findAllBySaleDate(saleDate: LocalDate): List<BuyerSaleProductQueryResult> {
            requestedDates += saleDate
            return results
        }

		override fun findDetailBySaleId(saleId: Long): BuyerSaleProductDetailQueryResult? = null
    }
}
