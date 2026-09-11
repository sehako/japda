package io.github.sehako.japda.product.domain.model

import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName

@DisplayName("상품 도메인")
class ProductTest {
	private val createdAt = Instant.parse("2026-09-10T00:00:00Z")

	@Test
	@DisplayName("유효한 상품 생성 시 입력을 정규화하고 초안 상태로 생성한다")
	fun 유효한_상품_생성_정규화된_초안_상품을_반환한다() {
		val product = Product.create(
			sellerId = 1L,
			name = "  한정판 상품  ",
			description = "  선택적인 상품 설명  ",
			createdAt = createdAt,
		)

		assertEquals(1L, product.sellerId)
		assertEquals("한정판 상품", product.name)
		assertEquals("선택적인 상품 설명", product.description)
		assertEquals(ProductStatus.DRAFT, product.status)
		assertEquals(createdAt, product.createdAt)
	}

	@Test
	@DisplayName("판매자 식별자가 양수가 아니면 상품 생성을 거부한다")
	fun 유효하지_않은_판매자_식별자_상품_생성을_거부한다() {
		val exception = assertFailsWith<ProductException> {
			Product.create(0L, "상품", null, createdAt)
		}

		assertEquals(ProductErrorCode.SELLER_ID_INVALID, exception.errorCode)
	}

	@Test
	@DisplayName("상품명이 없거나 공백이면 상품 생성을 거부한다")
	fun 상품명이_없거나_공백_상품_생성을_거부한다() {
		listOf(null, "   ").forEach { name ->
			val exception = assertFailsWith<ProductException> {
				Product.create(1L, name, null, createdAt)
			}

			assertEquals(ProductErrorCode.NAME_REQUIRED, exception.errorCode)
		}
	}

	@Test
	@DisplayName("정규화된 상품명이 100자를 초과하면 상품 생성을 거부한다")
	fun 상품명이_100자를_초과_상품_생성을_거부한다() {
		val exception = assertFailsWith<ProductException> {
			Product.create(1L, "가".repeat(101), null, createdAt)
		}

		assertEquals(ProductErrorCode.NAME_TOO_LONG, exception.errorCode)
	}

	@Test
	@DisplayName("설명이 공백이면 null로 정규화한다")
	fun 설명이_공백_null로_정규화한다() {
		val product = Product.create(1L, "상품", "   ", createdAt)

		assertNull(product.description)
	}

	@Test
	@DisplayName("정규화된 설명이 3000자를 초과하면 상품 생성을 거부한다")
	fun 설명이_3000자를_초과_상품_생성을_거부한다() {
		val exception = assertFailsWith<ProductException> {
			Product.create(1L, "상품", "가".repeat(3001), createdAt)
		}

		assertEquals(ProductErrorCode.DESCRIPTION_TOO_LONG, exception.errorCode)
	}

	@Test
	@DisplayName("상품명과 설명의 최대 길이는 허용한다")
	fun 상품명과_설명이_최대_길이_상품을_생성한다() {
		val product = Product.create(1L, "가".repeat(100), "나".repeat(3000), createdAt)

		assertEquals(100, product.name.length)
		assertEquals(3000, product.description?.length)
	}

	@Test
	@DisplayName("초안 상품을 준비 상태로 전환한다")
	fun 초안_상품_준비_상태로_전환한다() {
		val product = Product.create(1L, "상품", null, createdAt)

		product.markReady()

		assertEquals(ProductStatus.READY, product.status)
	}

	@Test
	@DisplayName("준비 상태 상품의 중복 이미지 등록을 거부한다")
	fun 준비_상태_상품_중복_전환을_거부한다() {
		val product = Product.create(1L, "상품", null, createdAt)
		product.markReady()

		val exception = assertFailsWith<ProductException> { product.markReady() }

		assertEquals(ProductErrorCode.IMAGES_ALREADY_REGISTERED, exception.errorCode)
	}
}
