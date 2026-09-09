package io.github.sehako.japda.product.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "products")
class Product internal constructor(
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	val id: Long? = null,

	@Column(name = "seller_id", nullable = false)
	val sellerId: Long,

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	val name: String,

	@Column(nullable = false, length = DESCRIPTION_MAX_LENGTH)
	val description: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = STATUS_MAX_LENGTH)
	val status: ProductStatus,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {

	companion object {
		const val NAME_MAX_LENGTH = 100
		const val DESCRIPTION_MAX_LENGTH = 5_000
		const val STATUS_MAX_LENGTH = 20

		private const val SELLER_ID_ERROR = "판매자 ID는 1 이상의 정수여야 합니다."
		private const val NAME_REQUIRED_ERROR = "상품명은 필수입니다."
		private const val NAME_LENGTH_ERROR = "상품명은 100자 이하여야 합니다."
		private const val DESCRIPTION_REQUIRED_ERROR = "상품 설명은 필수입니다."
		private const val DESCRIPTION_LENGTH_ERROR = "상품 설명은 5,000자 이하여야 합니다."

		fun create(
			sellerId: Long,
			name: String?,
			description: String?,
			createdAt: Instant,
		): Product {
			val normalizedName = name?.trimUnicodeWhitespace().orEmpty()
			val normalizedDescription = description?.trimUnicodeWhitespace().orEmpty()
			val errors = linkedMapOf<String, String>()

			if (sellerId <= 0) {
				errors["sellerId"] = SELLER_ID_ERROR
			}
			when {
				normalizedName.isEmpty() -> errors["name"] = NAME_REQUIRED_ERROR
				normalizedName.codePointLength() > NAME_MAX_LENGTH -> errors["name"] = NAME_LENGTH_ERROR
			}
			when {
				normalizedDescription.isEmpty() -> errors["description"] = DESCRIPTION_REQUIRED_ERROR
				normalizedDescription.codePointLength() > DESCRIPTION_MAX_LENGTH -> {
					errors["description"] = DESCRIPTION_LENGTH_ERROR
				}
			}

			if (errors.isNotEmpty()) {
				throw InvalidProductException(errors)
			}

			return Product(
				sellerId = sellerId,
				name = normalizedName,
				description = normalizedDescription,
				status = ProductStatus.DRAFT,
				createdAt = createdAt,
			)
		}

		private fun String.codePointLength(): Int = codePointCount(0, length)

		private fun String.trimUnicodeWhitespace(): String {
			var start = 0
			var end = length

			while (start < end) {
				val codePoint = codePointAt(start)
				if (!codePoint.isUnicodeWhitespace()) break
				start += Character.charCount(codePoint)
			}
			while (start < end) {
				val codePoint = codePointBefore(end)
				if (!codePoint.isUnicodeWhitespace()) break
				end -= Character.charCount(codePoint)
			}

			return substring(start, end)
		}

		private fun Int.isUnicodeWhitespace(): Boolean =
			Character.isWhitespace(this) || Character.isSpaceChar(this)
	}
}
