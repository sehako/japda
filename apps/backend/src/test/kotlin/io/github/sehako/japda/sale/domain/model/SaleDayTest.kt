package io.github.sehako.japda.sale.domain.model

import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("판매일 도메인")
class SaleDayTest {
	private val saleDate = LocalDate.of(2026, 9, 12)

	@Test
	@DisplayName("판매일을 생성하면 확정 정원과 빈 자리 수를 보관한다")
	fun 판매일_생성_정원과_빈_자리_수를_보관한다() {
		val saleDay = SaleDay.create(saleDate, 20)

		assertEquals(saleDate, saleDay.saleDate)
		assertEquals(20, saleDay.capacity)
		assertEquals(0, saleDay.registeredCount)
	}

	@Test
	@DisplayName("양수가 아닌 정원으로 판매일을 생성할 수 없다")
	fun 유효하지_않은_정원_생성을_거부한다() {
		listOf(0, -1).forEach { capacity ->
			assertFailsWith<IllegalArgumentException> {
				SaleDay.create(saleDate, capacity)
			}
		}
	}

	@Test
	@DisplayName("잔여 자리가 있으면 한 자리를 확보한다")
	fun 잔여_자리_확보_등록_수를_증가한다() {
		val saleDay = SaleDay.create(saleDate, 2)

		saleDay.reserve()
		saleDay.reserve()

		assertEquals(2, saleDay.registeredCount)
	}

	@Test
	@DisplayName("정원이 모두 차면 자리 확보를 거부하고 등록 수를 유지한다")
	fun 정원_초과_자리_확보를_거부한다() {
		val saleDay = SaleDay.create(saleDate, 1)
		saleDay.reserve()

		val exception = assertFailsWith<SaleException> { saleDay.reserve() }

		assertEquals(SaleErrorCode.CAPACITY_EXCEEDED, exception.errorCode)
		assertEquals(1, saleDay.registeredCount)
	}
}
