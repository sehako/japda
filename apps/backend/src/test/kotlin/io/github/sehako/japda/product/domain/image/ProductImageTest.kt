package io.github.sehako.japda.product.domain.image

import io.github.sehako.japda.product.domain.Product
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("상품 이미지")
class ProductImageTest {

	private val product = Product.create(
		sellerId = 1L,
		name = "상품",
		description = "설명",
		createdAt = Instant.parse("2026-09-09T03:00:00Z"),
	)

	@Test
	@DisplayName("상품 이미지 생성_검증된 메타데이터를 보존한다")
	fun 상품_이미지_생성_검증된_메타데이터를_보존한다() {
		val createdAt = Instant.parse("2026-09-09T04:00:00Z")

		val image = ProductImage.create(
			product = product,
			objectKey = "products/1/image.webp",
			contentType = "image/webp",
			sizeBytes = 1_024L,
			sortOrder = 2,
			representative = true,
			createdAt = createdAt,
		)

		assertEquals(product, image.product)
		assertEquals("products/1/image.webp", image.objectKey)
		assertEquals("image/webp", image.contentType)
		assertEquals(1_024L, image.sizeBytes)
		assertEquals(2, image.sortOrder)
		assertEquals(true, image.representative)
		assertEquals(createdAt, image.createdAt)
	}

	@Test
	@DisplayName("상품 이미지 생성_빈 객체 키를 거부한다")
	fun 상품_이미지_생성_빈_객체_키를_거부한다() {
		assertFailsWith<IllegalArgumentException> {
			createImage(objectKey = " ")
		}
	}

	@Test
	@DisplayName("상품 이미지 생성_허용하지 않는 MIME type을 거부한다")
	fun 상품_이미지_생성_허용하지_않는_MIME_type을_거부한다() {
		assertFailsWith<IllegalArgumentException> {
			createImage(contentType = "image/gif")
		}
	}

	@Test
	@DisplayName("상품 이미지 생성_0byte 크기를 거부한다")
	fun 상품_이미지_생성_0byte_크기를_거부한다() {
		assertFailsWith<IllegalArgumentException> {
			createImage(sizeBytes = 0L)
		}
	}

	@Test
	@DisplayName("상품 이미지 생성_10MiB를 초과하는 크기를 거부한다")
	fun 상품_이미지_생성_10MiB를_초과하는_크기를_거부한다() {
		assertFailsWith<IllegalArgumentException> {
			createImage(sizeBytes = 10L * 1024 * 1024 + 1)
		}
	}

	@Test
	@DisplayName("상품 이미지 생성_0보다 작거나 9보다 큰 정렬 순서를 거부한다")
	fun 상품_이미지_생성_0보다_작거나_9보다_큰_정렬_순서를_거부한다() {
		assertFailsWith<IllegalArgumentException> { createImage(sortOrder = -1) }
		assertFailsWith<IllegalArgumentException> { createImage(sortOrder = 10) }
	}

	private fun createImage(
		objectKey: String = "products/1/image.jpg",
		contentType: String = "image/jpeg",
		sizeBytes: Long = 1L,
		sortOrder: Int = 0,
	): ProductImage = ProductImage.create(
		product = product,
		objectKey = objectKey,
		contentType = contentType,
		sizeBytes = sizeBytes,
		sortOrder = sortOrder,
		representative = true,
		createdAt = Instant.parse("2026-09-09T04:00:00Z"),
	)
}
