package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.domain.image.ProductImageContent
import io.github.sehako.japda.product.domain.image.ProductImageStorageException
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse
import software.amazon.awssdk.services.s3.model.S3Exception

@DisplayName("S3 상품 이미지 저장소")
class S3ProductImageStorageTest {

	@Test
	@DisplayName("객체 저장_버킷과 키와 콘텐츠 메타데이터를 S3 요청에 전달한다")
	fun 객체_저장_버킷과_키와_콘텐츠_메타데이터를_S3_요청에_전달한다() {
		val client = RecordingS3Client()
		val storage = S3ProductImageStorage(client.proxy, "private-product-bucket")
		val bytes = byteArrayOf(1, 2, 3, 4)

		storage.store(
			objectKey = "images/products/41/image.webp",
			content = ProductImageContent(
				contentType = "image/webp",
				sizeBytes = 4,
				openStream = { ByteArrayInputStream(bytes) },
			),
		)

		val request = client.putRequest as PutObjectRequest
		assertEquals("private-product-bucket", request.bucket())
		assertEquals("images/products/41/image.webp", request.key())
		assertEquals("image/webp", request.contentType())
		assertEquals(4L, request.contentLength())
		val requestBytes = (client.putBody as RequestBody).contentStreamProvider().newStream().use { it.readBytes() }
		assertTrue(bytes.contentEquals(requestBytes))
	}

	@Test
	@DisplayName("보상 처리_삭제와 cleanup 태그 요청에 같은 버킷과 객체 키를 전달한다")
	fun 보상_처리_삭제와_cleanup_태그_요청에_같은_버킷과_객체_키를_전달한다() {
		val client = RecordingS3Client()
		val storage = S3ProductImageStorage(client.proxy, "private-product-bucket")

		storage.delete("images/products/41/image.jpg")
		storage.markForCleanup("images/products/41/image.jpg")

		val deleteRequest = client.deleteRequest as DeleteObjectRequest
		assertEquals("private-product-bucket", deleteRequest.bucket())
		assertEquals("images/products/41/image.jpg", deleteRequest.key())
		val taggingRequest = client.taggingRequest as PutObjectTaggingRequest
		assertEquals("private-product-bucket", taggingRequest.bucket())
		assertEquals("images/products/41/image.jpg", taggingRequest.key())
		assertEquals(1, taggingRequest.tagging().tagSet().size)
		assertEquals("cleanup", taggingRequest.tagging().tagSet().single().key())
		assertEquals("true", taggingRequest.tagging().tagSet().single().value())
	}

	@Test
	@DisplayName("S3 실패 변환_SDK 내부 정보가 공개 예외 메시지에 포함되지 않는다")
	fun S3_실패_변환_SDK_내부_정보가_공개_예외_메시지에_포함되지_않는다() {
		val client = RecordingS3Client(failure = S3Exception.builder().message("민감한 S3 응답").build())
		val storage = S3ProductImageStorage(client.proxy, "private-product-bucket")

		val exception = assertFailsWith<ProductImageStorageException> {
			storage.delete("secret/object-key.jpg")
		}

		assertEquals("상품 이미지 저장소를 사용할 수 없습니다.", exception.message)
		assertFalse(exception.message.orEmpty().contains("private-product-bucket"))
		assertFalse(exception.message.orEmpty().contains("secret/object-key.jpg"))
		assertFalse(exception.message.orEmpty().contains("민감한 S3 응답"))
	}

	private class RecordingS3Client(
		private val failure: RuntimeException? = null,
	) {
		var putRequest: Any? = null
		var putBody: Any? = null
		var deleteRequest: Any? = null
		var taggingRequest: Any? = null

		val proxy: S3Client = Proxy.newProxyInstance(
			S3Client::class.java.classLoader,
			arrayOf(S3Client::class.java),
		) { _, method, arguments ->
			when (method.name) {
				"putObject" -> {
					failure?.let { throw it }
					putRequest = arguments[0]
					putBody = arguments[1]
					PutObjectResponse.builder().build()
				}
				"deleteObject" -> {
					failure?.let { throw it }
					deleteRequest = arguments[0]
					DeleteObjectResponse.builder().build()
				}
				"putObjectTagging" -> {
					failure?.let { throw it }
					taggingRequest = arguments[0]
					PutObjectTaggingResponse.builder().build()
				}
				"serviceName" -> "s3"
				"close" -> Unit
				"toString" -> "RecordingS3Client"
				else -> error("지원하지 않는 S3Client 호출입니다: ${method.name}")
			}
		} as S3Client
	}
}
