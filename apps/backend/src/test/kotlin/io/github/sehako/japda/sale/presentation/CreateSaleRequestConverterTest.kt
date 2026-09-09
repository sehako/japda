package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.sale.application.CreateSaleDto
import io.github.sehako.japda.sale.domain.InvalidSaleException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

@DisplayName("판매 등록 요청 Converter")
class CreateSaleRequestConverterTest {

	private val converter = CreateSaleRequestConverter()
	private val nodeFactory = JsonNodeFactory.instance

	@Test
	@DisplayName("요청 변환_헤더와 경로 및 JSON 값을 Application DTO로 변환한다")
	fun 요청_변환_헤더와_경로_및_JSON_값을_Application_DTO로_변환한다() {
		val request = CreateSaleRequest(
			price = nodeFactory.numberNode(10_000L),
			quantity = nodeFactory.numberNode(100L),
			startsAt = nodeFactory.stringNode("2026-09-09T14:00:00+09:00"),
			endsAt = nodeFactory.stringNode("2026-09-16T14:00:00+09:00"),
		)

		val dto = converter.convert("7", 11L, request)

		assertEquals(
			CreateSaleDto(
				productId = 11L,
				sellerId = 7L,
				price = 10_000L,
				quantity = 100L,
				startsAt = Instant.parse("2026-09-09T05:00:00Z"),
				endsAt = Instant.parse("2026-09-16T05:00:00Z"),
			),
			dto,
		)
	}

	@Test
	@DisplayName("요청 변환_누락된 값을 필드별 오류로 모두 반환한다")
	fun 요청_변환_누락된_값을_필드별_오류로_모두_반환한다() {
		val exception = assertFailsWith<InvalidSaleException> {
			converter.convert(null, 0L, CreateSaleRequest())
		}

		assertEquals(
			mapOf(
				"sellerId" to "판매자 ID는 필수입니다.",
				"productId" to "상품 ID는 1 이상의 정수여야 합니다.",
				"price" to "판매 가격은 필수입니다.",
				"quantity" to "판매 수량은 필수입니다.",
				"startsAt" to "판매 시작 시각은 필수입니다.",
				"endsAt" to "판매 종료 시각은 필수입니다.",
			),
			exception.errors,
		)
	}

	@Test
	@DisplayName("요청 변환_타입과 범위 및 offset이 올바르지 않은 값을 필드별 오류로 모두 반환한다")
	fun 요청_변환_타입과_범위_및_offset이_올바르지_않은_값을_필드별_오류로_모두_반환한다() {
		val request = CreateSaleRequest(
			price = nodeFactory.numberNode(1.5),
			quantity = nodeFactory.stringNode("100"),
			startsAt = nodeFactory.stringNode("2026-09-09T05:00:00"),
			endsAt = nodeFactory.booleanNode(true),
		)

		val exception = assertFailsWith<InvalidSaleException> {
			converter.convert("판매자", 0L, request)
		}

		assertEquals(
			mapOf(
				"sellerId" to "판매자 ID는 1 이상의 정수여야 합니다.",
				"productId" to "상품 ID는 1 이상의 정수여야 합니다.",
				"price" to "판매 가격은 1 이상의 정수여야 합니다.",
				"quantity" to "판매 수량은 1 이상의 정수여야 합니다.",
				"startsAt" to "판매 시작 시각은 offset을 포함한 ISO-8601 형식이어야 합니다.",
				"endsAt" to "판매 종료 시각은 offset을 포함한 ISO-8601 형식이어야 합니다.",
			),
			exception.errors,
		)
	}

	@Test
	@DisplayName("요청 변환_Long 범위를 벗어난 JSON 정수를 거절한다")
	fun 요청_변환_Long_범위를_벗어난_JSON_정수를_거절한다() {
		val request = validRequest().copy(
			price = nodeFactory.numberNode(java.math.BigInteger("9223372036854775808")),
		)

		val exception = assertFailsWith<InvalidSaleException> {
			converter.convert("7", 11L, request)
		}

		assertEquals(
			mapOf("price" to "판매 가격은 1 이상의 정수여야 합니다."),
			exception.errors,
		)
	}

	private fun validRequest() = CreateSaleRequest(
		price = nodeFactory.numberNode(10_000L),
		quantity = nodeFactory.numberNode(100L),
		startsAt = nodeFactory.stringNode("2026-09-09T05:00:00Z"),
		endsAt = nodeFactory.stringNode("2026-09-16T05:00:00Z"),
	)
}
