package io.github.sehako.japda.product.domain.image

import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "product_images")
class ProductImage private constructor(
	id: Long?,
	@field:Column(name = "product_id", nullable = false)
	val productId: Long,
	@field:Column(name = "object_key", nullable = false, unique = true, length = MAX_OBJECT_KEY_LENGTH)
	val objectKey: String,
	@field:Column(name = "content_type", nullable = false, length = MAX_CONTENT_TYPE_LENGTH)
	val contentType: String,
	@field:Column(name = "size_bytes", nullable = false)
	val sizeBytes: Long,
	@field:Column(name = "display_order", nullable = false)
	val displayOrder: Int,
	@field:Column(name = "is_representative", nullable = false)
	val isRepresentative: Boolean,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = id
		protected set

	companion object {
		private const val MIN_IMAGE_COUNT = 1
		private const val MAX_IMAGE_COUNT = 10
		private const val MAX_OBJECT_KEY_LENGTH = 500
		private const val MAX_CONTENT_TYPE_LENGTH = 20

		fun create(
			productId: Long,
			objectKey: String,
			contentType: String,
			sizeBytes: Long,
			displayOrder: Int,
			isRepresentative: Boolean,
			createdAt: Instant,
		): ProductImage = ProductImage(
			id = null,
			productId = productId,
			objectKey = objectKey,
			contentType = contentType,
			sizeBytes = sizeBytes,
			displayOrder = displayOrder,
			isRepresentative = isRepresentative,
			createdAt = createdAt,
		)

		fun validateRegistration(images: List<ProductImage>) {
			if (images.size !in MIN_IMAGE_COUNT..MAX_IMAGE_COUNT) {
				throw ProductException(ProductErrorCode.IMAGE_COUNT_INVALID)
			}
			if (images.count { it.isRepresentative } != 1 ||
				images.map { it.displayOrder }.sorted() != images.indices.toList()
			) {
				throw ProductException(ProductErrorCode.IMAGE_REPRESENTATIVE_INVALID)
			}
		}
	}
}
