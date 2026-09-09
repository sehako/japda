package io.github.sehako.japda.product.domain.image

import io.github.sehako.japda.product.domain.Product
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "product_images")
class ProductImage internal constructor(
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	val id: Long? = null,

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	val product: Product,

	@Column(name = "object_key", nullable = false, unique = true)
	val objectKey: String,

	@Column(name = "content_type", nullable = false, length = CONTENT_TYPE_MAX_LENGTH)
	val contentType: String,

	@Column(name = "size_bytes", nullable = false)
	val sizeBytes: Long,

	@Column(name = "sort_order", nullable = false)
	val sortOrder: Int,

	@Column(name = "is_representative", nullable = false)
	val representative: Boolean,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {

	companion object {
		const val CONTENT_TYPE_MAX_LENGTH = 20
		const val MAX_SIZE_BYTES = 10L * 1024 * 1024
		const val MIN_SORT_ORDER = 0
		const val MAX_SORT_ORDER = 9
		val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")

		fun create(
			product: Product,
			objectKey: String,
			contentType: String,
			sizeBytes: Long,
			sortOrder: Int,
			representative: Boolean,
			createdAt: Instant,
		): ProductImage {
			require(objectKey.isNotBlank()) { "객체 키는 필수입니다." }
			require(contentType in ALLOWED_CONTENT_TYPES) { "허용하지 않는 MIME type입니다." }
			require(sizeBytes in 1..MAX_SIZE_BYTES) { "이미지 크기는 1byte 이상 10MiB 이하여야 합니다." }
			require(sortOrder in MIN_SORT_ORDER..MAX_SORT_ORDER) { "정렬 순서는 0 이상 9 이하여야 합니다." }

			return ProductImage(
				product = product,
				objectKey = objectKey,
				contentType = contentType,
				sizeBytes = sizeBytes,
				sortOrder = sortOrder,
				representative = representative,
				createdAt = createdAt,
			)
		}
	}
}
