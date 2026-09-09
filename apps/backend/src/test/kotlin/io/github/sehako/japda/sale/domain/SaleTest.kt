package io.github.sehako.japda.sale.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("판매")
class SaleTest {

	private val now = Instant.parse("2026-09-09T08:00:00Z")
	private val startsAt = Instant.parse("2026-09-09T09:00:00Z")
	private val endsAt = Instant.parse("2026-09-16T09:00:00Z")

	@Test
	@DisplayName("판매 생성_요청 수량을 최초 수량과 잔여 수량에 설정한다")
	fun 판매_생성_요청_수량을_최초_수량과_잔여_수량에_설정한다() {
		val sale = Sale.create(
			productId = 10L,
			price = 10_000L,
			quantity = 100L,
			startsAt = startsAt,
			endsAt = endsAt,
			now = now,
		)

		assertEquals(10L, sale.productId)
		assertEquals(10_000L, sale.price)
		assertEquals(100L, sale.initialQuantity)
		assertEquals(100L, sale.remainingQuantity)
		assertEquals(startsAt, sale.startsAt)
		assertEquals(endsAt, sale.endsAt)
		assertEquals(now, sale.createdAt)
	}

	@Test
	@DisplayName("판매 생성_잘못된 모든 값의 오류를 함께 반환한다")
	fun 판매_생성_잘못된_모든_값의_오류를_함께_반환한다() {
		val exception = assertFailsWith<InvalidSaleException> {
			Sale.create(
				productId = 10L,
				price = 0L,
				quantity = -1L,
				startsAt = Instant.parse("2026-09-09T10:00:00Z"),
				endsAt = Instant.parse("2026-09-09T09:00:00Z"),
				now = now,
			)
		}

		assertEquals(
			mapOf(
				"price" to "판매 가격은 1 이상의 정수여야 합니다.",
				"quantity" to "판매 수량은 1 이상의 정수여야 합니다.",
				"endsAt" to "판매 종료 시각은 판매 시작 시각보다 늦어야 합니다.",
			),
			exception.errors,
		)
	}

	@Test
	@DisplayName("판매 생성_종료 시각이 현재 시각과 같으면 거부한다")
	fun 판매_생성_종료_시각이_현재_시각과_같으면_거부한다() {
		val exception = assertFailsWith<InvalidSaleException> {
			Sale.create(
				productId = 10L,
				price = 10_000L,
				quantity = 100L,
				startsAt = Instant.parse("2026-09-09T07:00:00Z"),
				endsAt = now,
				now = now,
			)
		}

		assertEquals(
			mapOf("endsAt" to "판매 종료 시각은 현재 시각보다 미래여야 합니다."),
			exception.errors,
		)
	}

	@Test
	@DisplayName("판매 상태_시작 시각 전에는 SCHEDULED이다")
	fun 판매_상태_시작_시각_전에는_SCHEDULED이다() {
		val sale = createSale()

		assertEquals(SaleStatus.SCHEDULED, sale.statusAt(Instant.parse("2026-09-09T08:59:59Z")))
	}

	@Test
	@DisplayName("판매 상태_시작 경계부터 ON_SALE이다")
	fun 판매_상태_시작_경계부터_ON_SALE이다() {
		val sale = createSale()

		assertEquals(SaleStatus.ON_SALE, sale.statusAt(startsAt))
	}

	@Test
	@DisplayName("판매 상태_기간 안에 잔여 수량이 없으면 SOLD_OUT이다")
	fun 판매_상태_기간_안에_잔여_수량이_없으면_SOLD_OUT이다() {
		val sale = createPersistedSale(remainingQuantity = 0L)

		assertEquals(SaleStatus.SOLD_OUT, sale.statusAt(Instant.parse("2026-09-10T09:00:00Z")))
	}

	@Test
	@DisplayName("판매 상태_시작 전이어도 잔여 수량이 없으면 SOLD_OUT이다")
	fun 판매_상태_시작_전이어도_잔여_수량이_없으면_SOLD_OUT이다() {
		val sale = createPersistedSale(remainingQuantity = 0L)

		assertEquals(SaleStatus.SOLD_OUT, sale.statusAt(Instant.parse("2026-09-09T08:59:59Z")))
	}

	@Test
	@DisplayName("판매 상태_종료 경계부터 ENDED이다")
	fun 판매_상태_종료_경계부터_ENDED이다() {
		val sale = createSale()

		assertEquals(SaleStatus.ENDED, sale.statusAt(endsAt))
	}

	@Test
	@DisplayName("판매 상태_종료된 품절 판매는 ENDED가 우선한다")
	fun 판매_상태_종료된_품절_판매는_ENDED가_우선한다() {
		val sale = createPersistedSale(remainingQuantity = 0L)

		assertEquals(SaleStatus.ENDED, sale.statusAt(endsAt))
	}

	private fun createSale(): Sale = Sale.create(
		productId = 10L,
		price = 10_000L,
		quantity = 100L,
		startsAt = startsAt,
		endsAt = endsAt,
		now = now,
	)

	private fun createPersistedSale(remainingQuantity: Long): Sale = Sale(
		id = 1L,
		productId = 10L,
		price = 10_000L,
		initialQuantity = 100L,
		remainingQuantity = remainingQuantity,
		startsAt = startsAt,
		endsAt = endsAt,
		createdAt = now,
	)
}
