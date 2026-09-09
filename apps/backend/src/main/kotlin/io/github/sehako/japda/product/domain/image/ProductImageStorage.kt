package io.github.sehako.japda.product.domain.image

import java.io.InputStream

data class ProductImageContent(
	val contentType: String,
	val sizeBytes: Long,
	val openStream: () -> InputStream,
)

interface ProductImageStorage {

	fun store(objectKey: String, content: ProductImageContent)

	fun delete(objectKey: String)

	fun markForCleanup(objectKey: String)
}

class ProductImageStorageException(
	cause: Throwable,
) : RuntimeException("상품 이미지 저장소를 사용할 수 없습니다.", cause)
