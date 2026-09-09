package io.github.sehako.japda.product.presentation.image

import io.github.sehako.japda.product.application.image.InvalidProductImageUploadException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile

@DisplayName("상품 이미지 업로드 요청 변환기")
class UploadProductImagesRequestConverterTest {

	private val converter = UploadProductImagesRequestConverter()

	@Test
	@DisplayName("요청 변환_경로와 헤더 및 multipart 값을 Application DTO로 변환한다")
	fun 요청_변환_경로와_헤더_및_multipart_값을_Application_DTO로_변환한다() {
		val first = MockMultipartFile("files", "front.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
		val second = MockMultipartFile("files", "side.webp", "image/webp", byteArrayOf(4, 5))

		val dto = converter.convert("123", "456", listOf(first, second), "1")

		assertEquals(123L, dto.productId)
		assertEquals(456L, dto.sellerId)
		assertEquals(1, dto.representativeIndex)
		assertEquals(listOf("image/jpeg", "image/webp"), dto.files.map { it.contentType })
		assertEquals(listOf(3L, 2L), dto.files.map { it.sizeBytes })
		assertContentEquals(byteArrayOf(1, 2, 3), dto.files[0].openStream().use { it.readAllBytes() })
		assertContentEquals(byteArrayOf(1, 2, 3), dto.files[0].openStream().use { it.readAllBytes() })
	}

	@Test
	@DisplayName("요청 변환_양수가 아닌 상품과 판매자 ID를 필드 오류로 거절한다")
	fun 요청_변환_양수가_아닌_상품과_판매자_ID를_필드_오류로_거절한다() {
		val exception = assertFailsWith<InvalidProductImageUploadException> {
			converter.convert("0", "-1", emptyList(), "0")
		}

		assertEquals("상품 ID는 1 이상의 정수여야 합니다.", exception.errors["productId"])
		assertEquals("판매자 ID는 1 이상의 정수여야 합니다.", exception.errors["sellerId"])
	}

	@Test
	@DisplayName("요청 변환_읽을 수 없는 식별자와 대표 인덱스를 필드 오류로 거절한다")
	fun 요청_변환_읽을_수_없는_식별자와_대표_인덱스를_필드_오류로_거절한다() {
		val exception = assertFailsWith<InvalidProductImageUploadException> {
			converter.convert("상품", null, emptyList(), "대표")
		}

		assertEquals(setOf("productId", "sellerId", "representativeIndex"), exception.errors.keys)
	}
}
