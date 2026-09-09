package io.github.sehako.japda.product.application.image

import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("상품 이미지 파일 검증기")
class ProductImageFileValidatorTest {

	private val validator = ProductImageFileValidator()

	@Test
	@DisplayName("허용 이미지 검증_선언 형식과 시그니처가 일치하면 검증된 확장자를 반환한다")
	fun 허용_이미지_검증_선언_형식과_시그니처가_일치하면_검증된_확장자를_반환한다() {
		val files = listOf(
			file("image/jpeg", jpegBytes()),
			file("image/png", pngBytes()),
			file("image/webp", webpBytes()),
		)

		val validated = validator.validate(files, representativeIndex = 1)

		assertEquals(listOf("image/jpeg", "image/png", "image/webp"), validated.map { it.contentType })
		assertEquals(listOf("jpg", "png", "webp"), validated.map { it.extension })
		assertEquals(files.map { it.sizeBytes }, validated.map { it.sizeBytes })
	}

	@Test
	@DisplayName("파일 개수 검증_파일이 없거나 11장이면 files 오류를 반환한다")
	fun 파일_개수_검증_파일이_없거나_11장이면_files_오류를_반환한다() {
		val emptyException = assertFailsWith<InvalidProductImageUploadException> {
			validator.validate(emptyList(), representativeIndex = 0)
		}
		val tooManyException = assertFailsWith<InvalidProductImageUploadException> {
			validator.validate(List(11) { file("image/jpeg", jpegBytes()) }, representativeIndex = 0)
		}

		assertEquals(setOf("files", "representativeIndex"), emptyException.errors.keys)
		assertEquals(setOf("files"), tooManyException.errors.keys)
	}

	@Test
	@DisplayName("대표 인덱스 검증_파일 배열 범위를 벗어나면 representativeIndex 오류를 반환한다")
	fun 대표_인덱스_검증_파일_배열_범위를_벗어나면_representativeIndex_오류를_반환한다() {
		val exception = assertFailsWith<InvalidProductImageUploadException> {
			validator.validate(listOf(file("image/jpeg", jpegBytes())), representativeIndex = 1)
		}

		assertEquals(setOf("representativeIndex"), exception.errors.keys)
	}

	@Test
	@DisplayName("파일 형식 검증_선언 형식과 시그니처가 다르면 files 오류를 반환한다")
	fun 파일_형식_검증_선언_형식과_시그니처가_다르면_files_오류를_반환한다() {
		val exception = assertFailsWith<InvalidProductImageUploadException> {
			validator.validate(listOf(file("image/png", jpegBytes())), representativeIndex = 0)
		}

		assertEquals(setOf("files"), exception.errors.keys)
	}

	@Test
	@DisplayName("WebP 형식 검증_RIFF 뒤의 WEBP 식별자가 없으면 files 오류를 반환한다")
	fun WebP_형식_검증_RIFF_뒤의_WEBP_식별자가_없으면_files_오류를_반환한다() {
		val invalidWebp = "RIFF\u0004\u0000\u0000\u0000JPEG".toByteArray(Charsets.US_ASCII)

		val exception = assertFailsWith<InvalidProductImageUploadException> {
			validator.validate(listOf(file("image/webp", invalidWebp)), representativeIndex = 0)
		}

		assertEquals(setOf("files"), exception.errors.keys)
	}

	@Test
	@DisplayName("빈 파일 검증_크기가 0이면 files 오류를 반환한다")
	fun 빈_파일_검증_크기가_0이면_files_오류를_반환한다() {
		val exception = assertFailsWith<InvalidProductImageUploadException> {
			validator.validate(listOf(file("image/jpeg", byteArrayOf())), representativeIndex = 0)
		}

		assertEquals(setOf("files"), exception.errors.keys)
	}

	@Test
	@DisplayName("개별 용량 검증_10MiB를 초과하면 413용 예외를 반환한다")
	fun 개별_용량_검증_10MiB를_초과하면_413용_예외를_반환한다() {
		val exception = assertFailsWith<ProductImagePayloadTooLargeException> {
			validator.validate(
				listOf(file("image/jpeg", jpegBytes(), sizeBytes = 10L * 1024 * 1024 + 1)),
				representativeIndex = 0,
			)
		}

		assertEquals(setOf("files"), exception.errors.keys)
	}

	@Test
	@DisplayName("합계 용량 검증_50MiB를 초과하면 413용 예외를 반환한다")
	fun 합계_용량_검증_50MiB를_초과하면_413용_예외를_반환한다() {
		val files = List(6) { file("image/jpeg", jpegBytes(), sizeBytes = 9L * 1024 * 1024) }

		val exception = assertFailsWith<ProductImagePayloadTooLargeException> {
			validator.validate(files, representativeIndex = 0)
		}

		assertEquals(setOf("files"), exception.errors.keys)
	}

	@Test
	@DisplayName("경계 용량 검증_개별 10MiB와 합계 50MiB는 허용한다")
	fun 경계_용량_검증_개별_10MiB와_합계_50MiB는_허용한다() {
		val files = List(5) { file("image/jpeg", jpegBytes(), sizeBytes = 10L * 1024 * 1024) }

		val validated = validator.validate(files, representativeIndex = 4)

		assertEquals(5, validated.size)
	}

	private fun file(
		contentType: String?,
		bytes: ByteArray,
		sizeBytes: Long = bytes.size.toLong(),
	): ProductImageUploadFile = ProductImageUploadFile(
		contentType = contentType,
		sizeBytes = sizeBytes,
		openStream = { ByteArrayInputStream(bytes) },
	)

	private fun jpegBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

	private fun pngBytes(): ByteArray = byteArrayOf(
		0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
	)

	private fun webpBytes(): ByteArray = "RIFF\u0004\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)
}
