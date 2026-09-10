package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.application.image.ProductImageStorage
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.io.InputStream
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@Component
class S3ProductImageStorage(
	private val s3Client: S3Client,
	@Value("\${product.image.s3.bucket}") private val bucket: String,
) : ProductImageStorage {
	override fun upload(
		objectKey: String,
		contentType: String,
		sizeBytes: Long,
		inputStream: InputStream,
	) {
		val request = PutObjectRequest.builder()
			.bucket(bucket)
			.key(objectKey)
			.contentType(contentType)
			.contentLength(sizeBytes)
			.build()

		try {
			s3Client.putObject(request, RequestBody.fromInputStream(inputStream, sizeBytes))
		} catch (exception: SdkException) {
			throw ProductException(ProductErrorCode.IMAGE_STORAGE_FAILED)
		}
	}

	override fun delete(objectKey: String) {
		val request = DeleteObjectRequest.builder()
			.bucket(bucket)
			.key(objectKey)
			.build()

		try {
			s3Client.deleteObject(request)
		} catch (exception: SdkException) {
			throw ProductException(ProductErrorCode.IMAGE_STORAGE_FAILED)
		}
	}
}
