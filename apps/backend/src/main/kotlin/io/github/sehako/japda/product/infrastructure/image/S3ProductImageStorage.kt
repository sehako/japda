package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.domain.image.ProductImageContent
import io.github.sehako.japda.product.domain.image.ProductImageStorage
import io.github.sehako.japda.product.domain.image.ProductImageStorageException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.s3.model.Tagging

class S3ProductImageStorage(
	private val s3Client: S3Client,
	private val bucket: String,
) : ProductImageStorage {

	override fun store(objectKey: String, content: ProductImageContent) {
		val request = PutObjectRequest.builder()
			.bucket(bucket)
			.key(objectKey)
			.contentType(content.contentType)
			.contentLength(content.sizeBytes)
			.build()

		execute {
			s3Client.putObject(
				request,
				RequestBody.fromContentProvider(
					{ content.openStream() },
					content.sizeBytes,
					content.contentType,
				),
			)
		}
	}

	override fun delete(objectKey: String) {
		val request = DeleteObjectRequest.builder()
			.bucket(bucket)
			.key(objectKey)
			.build()
		execute { s3Client.deleteObject(request) }
	}

	override fun markForCleanup(objectKey: String) {
		val cleanupTag = Tag.builder()
			.key(CLEANUP_TAG_KEY)
			.value(CLEANUP_TAG_VALUE)
			.build()
		val request = PutObjectTaggingRequest.builder()
			.bucket(bucket)
			.key(objectKey)
			.tagging(Tagging.builder().tagSet(cleanupTag).build())
			.build()
		execute { s3Client.putObjectTagging(request) }
	}

	private fun execute(operation: () -> Unit) {
		try {
			operation()
		} catch (exception: SdkException) {
			throw ProductImageStorageException(exception)
		}
	}

	companion object {
		private const val CLEANUP_TAG_KEY = "cleanup"
		private const val CLEANUP_TAG_VALUE = "true"
	}
}
