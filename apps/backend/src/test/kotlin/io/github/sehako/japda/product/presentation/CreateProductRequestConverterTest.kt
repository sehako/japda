package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.product.application.CreateProductDto
import io.github.sehako.japda.product.domain.InvalidProductException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

@DisplayName("상품 등록 요청 Converter")
class CreateProductRequestConverterTest {

	private val converter = CreateProductRequestConverter()
	private val nodeFactory = JsonNodeFactory.instance

	@Test
	@DisplayName("요청 변환_헤더와 문자열 본문을 Application DTO로 변환한다")
	fun 요청_변환_헤더와_문자열_본문을_Application_DTO로_변환한다() {
		val request = CreateProductRequest(
			name = nodeFactory.stringNode(" 상품 "),
			description = nodeFactory.stringNode(" 설명 "),
		)

		val dto = converter.convert("123", request)

		assertEquals(CreateProductDto(123L, " 상품 ", " 설명 "), dto)
	}

	@Test
	@DisplayName("요청 변환_판매자 헤더가 없거나 올바르지 않으면 필드 오류를 반환한다")
	fun 요청_변환_판매자_헤더가_없거나_올바르지_않으면_필드_오류를_반환한다() {
		val request = CreateProductRequest()

		val missing = assertFailsWith<InvalidProductException> {
			converter.convert(null, request)
		}
		val invalid = assertFailsWith<InvalidProductException> {
			converter.convert("0", request)
		}

		assertEquals(mapOf("sellerId" to "판매자 ID는 필수입니다."), missing.errors)
		assertEquals(
			mapOf("sellerId" to "판매자 ID는 1 이상의 정수여야 합니다."),
			invalid.errors,
		)
	}

	@Test
	@DisplayName("요청 변환_문자열이 아닌 본문 필드는 요청 본문 오류를 반환한다")
	fun 요청_변환_문자열이_아닌_본문_필드는_요청_본문_오류를_반환한다() {
		val request = CreateProductRequest(
			name = nodeFactory.numberNode(123),
			description = nodeFactory.stringNode("설명"),
		)

		val exception = assertFailsWith<InvalidProductException> {
			converter.convert("123", request)
		}

		assertEquals(mapOf("request" to "요청 본문을 읽을 수 없습니다."), exception.errors)
	}
}
