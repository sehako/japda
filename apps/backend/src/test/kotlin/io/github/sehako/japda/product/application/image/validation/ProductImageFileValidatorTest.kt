package io.github.sehako.japda.product.application.image.validation

import io.github.sehako.japda.product.application.image.file.ProductImageFile
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("상품 이미지 파일 검증기")
class ProductImageFileValidatorTest {

	private val validator = ProductImageFileValidator()

	@Test
	@DisplayName("JPEG PNG WebP 시그니처를 서버 미디어 타입으로 판별한다")
	fun 지원_이미지_시그니처_미디어_타입을_판별한다() {
		assertEquals("image/jpeg", validator.validate(listOf(file(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))), 0).single().contentType)
		assertEquals("image/png", validator.validate(listOf(file(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))), 0).single().contentType)
		assertEquals("image/webp", validator.validate(listOf(file("RIFF0000WEBP".encodeToByteArray())), 0).single().contentType)
	}

	@Test
	@DisplayName("빈 파일은 파일 오류로 거부한다")
	fun 빈_파일_파일_오류로_거부한다() {
		val exception = assertFailsWith<ProductException> { validator.validate(listOf(file(byteArrayOf())), 0) }
		assertEquals(ProductErrorCode.IMAGE_FILE_INVALID, exception.errorCode)
	}

	@Test
	@DisplayName("불완전하거나 지원하지 않는 시그니처는 형식 오류로 거부한다")
	fun 지원하지_않는_시그니처_형식_오류로_거부한다() {
		val exception = assertFailsWith<ProductException> { validator.validate(listOf(file(byteArrayOf(0xff.toByte(), 0xd8.toByte()))), 0) }
		assertEquals(ProductErrorCode.IMAGE_FORMAT_UNSUPPORTED, exception.errorCode)
	}

	@Test
	@DisplayName("대표 인덱스가 파일 범위를 벗어나면 거부한다")
	fun 대표_인덱스_범위_초과_거부한다() {
		val exception = assertFailsWith<ProductException> { validator.validate(listOf(file(jpeg())), 1) }
		assertEquals(ProductErrorCode.IMAGE_REPRESENTATIVE_INVALID, exception.errorCode)
	}

	@Test
	@DisplayName("파일이 11장이면 거부한다")
	fun 파일_11장_거부한다() {
		val exception = assertFailsWith<ProductException> { validator.validate(List(11) { file(jpeg()) }, 0) }
		assertEquals(ProductErrorCode.IMAGE_COUNT_INVALID, exception.errorCode)
	}

	private fun file(bytes: ByteArray): ProductImageFile = object : ProductImageFile {
		override val size: Long = bytes.size.toLong()
		override fun openStream() = ByteArrayInputStream(bytes)
	}

	private fun jpeg() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
}
