package io.github.sehako.japda.product.domain.image

import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("상품 이미지 도메인")
class ProductImageTest {
	private val createdAt = Instant.parse("2026-09-10T00:00:00Z")

	@Test
	@DisplayName("이미지 1장과 10장 등록을 허용한다")
	fun 이미지_1장과_10장_등록을_허용한다() {
		val oneImage = listOf(image(displayOrder = 0, isRepresentative = true))
		val tenImages = (0 until 10).map { order ->
			image(displayOrder = order, isRepresentative = order == 9)
		}

		ProductImage.validateRegistration(oneImage)
		ProductImage.validateRegistration(tenImages)
	}

	@Test
	@DisplayName("이미지가 0장 또는 11장이면 등록을 거부한다")
	fun 이미지_0장_또는_11장_등록을_거부한다() {
		listOf(
			emptyList(),
			(0 until 11).map { order -> image(displayOrder = order, isRepresentative = order == 0) },
		).forEach { images ->
			val exception = assertFailsWith<ProductException> {
				ProductImage.validateRegistration(images)
			}

			assertEquals(ProductErrorCode.IMAGE_COUNT_INVALID, exception.errorCode)
		}
	}

	@Test
	@DisplayName("대표 이미지가 정확히 1장이 아니면 등록을 거부한다")
	fun 대표_이미지가_정확히_1장이_아니면_등록을_거부한다() {
		listOf(
			listOf(image(0, false)),
			listOf(image(0, true), image(1, true)),
		).forEach { images ->
			val exception = assertFailsWith<ProductException> {
				ProductImage.validateRegistration(images)
			}

			assertEquals(ProductErrorCode.IMAGE_REPRESENTATIVE_INVALID, exception.errorCode)
		}
	}

	@Test
	@DisplayName("표시 순서는 0부터 이미지 개수 미만까지 중복 없이 이어져야 한다")
	fun 유효하지_않은_표시_순서_등록을_거부한다() {
		listOf(
			listOf(image(1, true)),
			listOf(image(0, true), image(0, false)),
			listOf(image(0, true), image(2, false)),
		).forEach { images ->
			val exception = assertFailsWith<ProductException> {
				ProductImage.validateRegistration(images)
			}

			assertEquals(ProductErrorCode.IMAGE_REPRESENTATIVE_INVALID, exception.errorCode)
		}
	}

	private fun image(
		displayOrder: Int,
		isRepresentative: Boolean,
	): ProductImage = ProductImage.create(
		productId = 42L,
		objectKey = "products/42/request/image-$displayOrder",
		contentType = "image/jpeg",
		sizeBytes = 1024L,
		displayOrder = displayOrder,
		isRepresentative = isRepresentative,
		createdAt = createdAt,
	)
}
