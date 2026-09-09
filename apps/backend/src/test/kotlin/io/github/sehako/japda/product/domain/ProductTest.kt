package io.github.sehako.japda.product.domain

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("상품")
class ProductTest {

	@Test
	@DisplayName("상품 생성_앞뒤 Unicode 공백을 제거하고 내부 공백은 보존한다")
	fun 상품_생성_앞뒤_Unicode_공백을_제거하고_내부_공백은_보존한다() {
		val product = Product.create(
			sellerId = 1L,
			name = "\u00A0 한정판  상품 \u3000",
			description = "\u2003 상품  설명 \u00A0",
			createdAt = Instant.parse("2026-09-09T03:00:00Z"),
		)

		assertEquals("한정판  상품", product.name)
		assertEquals("상품  설명", product.description)
	}

	@Test
	@DisplayName("상품 생성_이모지를 Unicode code point 한 글자로 계산한다")
	fun 상품_생성_이모지를_Unicode_code_point_한_글자로_계산한다() {
		val product = Product.create(
			sellerId = 1L,
			name = "😀".repeat(100),
			description = "😀".repeat(5_000),
			createdAt = Instant.parse("2026-09-09T03:00:00Z"),
		)

		assertEquals(200, product.name.length)
		assertEquals(10_000, product.description.length)
	}

	@Test
	@DisplayName("상품 생성_잘못된 모든 필드의 오류를 함께 반환한다")
	fun 상품_생성_잘못된_모든_필드의_오류를_함께_반환한다() {
		val exception = assertFailsWith<InvalidProductException> {
			Product.create(
				sellerId = 0L,
				name = "😀".repeat(101),
				description = " ",
				createdAt = Instant.parse("2026-09-09T03:00:00Z"),
			)
		}

		assertEquals(
			mapOf(
				"sellerId" to "판매자 ID는 1 이상의 정수여야 합니다.",
				"name" to "상품명은 100자 이하여야 합니다.",
				"description" to "상품 설명은 필수입니다.",
			),
			exception.errors,
		)
	}

	@Test
	@DisplayName("상품 생성_초기 상태와 생성 시각을 설정한다")
	fun 상품_생성_초기_상태와_생성_시각을_설정한다() {
		val createdAt = Instant.parse("2026-09-09T03:00:00Z")

		val product = Product.create(1L, "상품", "설명", createdAt)

		assertEquals(ProductStatus.DRAFT, product.status)
		assertEquals(createdAt, product.createdAt)
	}
}
