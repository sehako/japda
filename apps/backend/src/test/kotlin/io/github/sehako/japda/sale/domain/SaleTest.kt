package io.github.sehako.japda.sale.domain

import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("판매 일정 도메인")
class SaleTest {
	private val saleDate = LocalDate.of(2026, 9, 12)
	private val createdAt = Instant.parse("2026-09-11T00:00:01Z")

	@Test
	@DisplayName("유효한 판매 일정을 생성하고 한국 시간 기준 판매 시작과 종료 시각을 계산한다")
	fun 유효한_판매_일정_생성_판매_시각을_계산한다() {
		val sale = Sale.create(10L, 1L, saleDate, 35_000L, 100, createdAt)

		assertEquals(10L, sale.productId)
		assertEquals(1L, sale.sellerId)
		assertEquals(saleDate, sale.saleDate)
		assertEquals(35_000L, sale.price)
		assertEquals(100, sale.quantity)
		assertEquals(Instant.parse("2026-09-11T15:00:00Z"), sale.startsAt)
		assertEquals(Instant.parse("2026-09-12T15:00:00Z"), sale.endsAt)
		assertEquals(createdAt, sale.createdAt)
	}

	@Test
	@DisplayName("판매자 식별자가 양수가 아니면 판매 일정 생성을 거부한다")
	fun 유효하지_않은_판매자_식별자_생성을_거부한다() {
		val exception = assertFailsWith<SaleException> {
			Sale.create(10L, 0L, saleDate, 35_000L, 100, createdAt)
		}

		assertEquals(SaleErrorCode.SELLER_ID_INVALID, exception.errorCode)
	}

	@Test
	@DisplayName("상품 식별자가 없거나 양수가 아니면 판매 일정 생성을 거부한다")
	fun 유효하지_않은_상품_식별자_생성을_거부한다() {
		listOf(null, 0L, -1L).forEach { productId ->
			val exception = assertFailsWith<SaleException> {
				Sale.create(productId, 1L, saleDate, 35_000L, 100, createdAt)
			}

			assertEquals(SaleErrorCode.PRODUCT_ID_INVALID, exception.errorCode)
		}
	}

	@Test
	@DisplayName("판매일이 없으면 판매 일정 생성을 거부한다")
	fun 판매일이_없으면_생성을_거부한다() {
		val exception = assertFailsWith<SaleException> {
			Sale.create(10L, 1L, null, 35_000L, 100, createdAt)
		}

		assertEquals(SaleErrorCode.DATE_REQUIRED, exception.errorCode)
	}

	@Test
	@DisplayName("가격이 없거나 양수가 아니면 판매 일정 생성을 거부한다")
	fun 유효하지_않은_가격_생성을_거부한다() {
		listOf(null, 0L, -1L).forEach { price ->
			val exception = assertFailsWith<SaleException> {
				Sale.create(10L, 1L, saleDate, price, 100, createdAt)
			}

			assertEquals(SaleErrorCode.PRICE_INVALID, exception.errorCode)
		}
	}

	@Test
	@DisplayName("판매 수량이 없거나 양수가 아니면 판매 일정 생성을 거부한다")
	fun 유효하지_않은_수량_생성을_거부한다() {
		listOf(null, 0, -1).forEach { quantity ->
			val exception = assertFailsWith<SaleException> {
				Sale.create(10L, 1L, saleDate, 35_000L, quantity, createdAt)
			}

			assertEquals(SaleErrorCode.QUANTITY_INVALID, exception.errorCode)
		}
	}

	@Test
	@DisplayName("등록 시작 정각부터 판매 시작 직전까지 등록을 허용한다")
	fun 등록_가능_시간_경계를_허용한다() {
		Sale.validateRegistrationTime(saleDate, Instant.parse("2026-09-11T00:00:00Z"))
		Sale.validateRegistrationTime(saleDate, Instant.parse("2026-09-11T14:59:59.999999999Z"))
	}

	@Test
	@DisplayName("등록 시작 전과 판매 시작 이후에는 등록을 거부한다")
	fun 등록_불가능_시간을_거부한다() {
		listOf(
			Instant.parse("2026-09-10T23:59:59.999999999Z"),
			Instant.parse("2026-09-11T15:00:00Z"),
		).forEach { now ->
			val exception = assertFailsWith<SaleException> {
				Sale.validateRegistrationTime(saleDate, now)
			}

			assertEquals(SaleErrorCode.REGISTRATION_CLOSED, exception.errorCode)
		}
	}
}
