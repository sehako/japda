package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

@DisplayName("S3 상품 이미지 저장소")
class S3ProductImageStorageTest {
	private val s3Client = mock(S3Client::class.java)
	private val storage = S3ProductImageStorage(s3Client, "private-product-images")

	@Test
	@DisplayName("업로드 시 비공개 버킷에 객체 키, 검증된 미디어 타입, 정확한 본문을 전달한다")
	fun 업로드_비공개_버킷에_객체_키_미디어_타입_본문을_전달한다() {
		val expectedBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
		lateinit var request: PutObjectRequest
		lateinit var uploadedBytes: ByteArray
		doAnswer { invocation ->
			request = invocation.getArgument(0)
			val body = invocation.getArgument<RequestBody>(1)
			uploadedBytes = body.contentStreamProvider().newStream().use { it.readAllBytes() }
			null
		}.`when`(s3Client).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))

		storage.upload("products/42/request/image.png", "image/png", expectedBytes.size.toLong(), ByteArrayInputStream(expectedBytes))

		assertEquals("private-product-images", request.bucket())
		assertEquals("products/42/request/image.png", request.key())
		assertEquals("image/png", request.contentType())
		assertEquals(expectedBytes.size.toLong(), request.contentLength())
		assertContentEquals(expectedBytes, uploadedBytes)
	}

	@Test
	@DisplayName("업로드 SDK 실패를 상품 이미지 저장 실패로 변환한다")
	fun 업로드_SDK_실패_상품_이미지_저장_실패로_변환한다() {
		doThrow(S3Exception.builder().message("put failed").build())
			.`when`(s3Client).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))

		val exception = assertFailsWith<ProductException> {
			storage.upload("products/42/request/image.jpg", "image/jpeg", 3L, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
		}

		assertEquals(ProductErrorCode.IMAGE_STORAGE_FAILED, exception.errorCode)
	}

	@Test
	@DisplayName("삭제 SDK 실패를 상품 이미지 저장 실패로 변환한다")
	fun 삭제_SDK_실패_상품_이미지_저장_실패로_변환한다() {
		doThrow(S3Exception.builder().message("delete failed").build())
			.`when`(s3Client).deleteObject(any(DeleteObjectRequest::class.java))

		val exception = assertFailsWith<ProductException> {
			storage.delete("products/42/request/image.webp")
		}

		assertEquals(ProductErrorCode.IMAGE_STORAGE_FAILED, exception.errorCode)
	}
}
