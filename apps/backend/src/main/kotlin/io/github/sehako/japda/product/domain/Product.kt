package io.github.sehako.japda.product.domain

import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
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
class Product private constructor(
    id: Long?,
    @field:Column(name = "seller_id", nullable = false)
    val sellerId: Long,
    @field:Column(nullable = false, length = MAX_NAME_LENGTH)
    val name: String,
    @field:Column(length = MAX_DESCRIPTION_LENGTH)
    val description: String?,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = MAX_STATUS_LENGTH)
    val status: ProductStatus,
    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    companion object {
        private const val MAX_NAME_LENGTH = 100
        private const val MAX_DESCRIPTION_LENGTH = 3000
        private const val MAX_STATUS_LENGTH = 20

        fun create(
            sellerId: Long,
            name: String?,
            description: String?,
            createdAt: Instant,
        ): Product {
            if (sellerId <= 0) {
                throw ProductException(ProductErrorCode.SELLER_ID_INVALID)
            }

            val normalizedName = name?.trim()
            if (normalizedName.isNullOrEmpty()) {
                throw ProductException(ProductErrorCode.NAME_REQUIRED)
            }
            if (normalizedName.length > MAX_NAME_LENGTH) {
                throw ProductException(ProductErrorCode.NAME_TOO_LONG)
            }

            val normalizedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedDescription != null && normalizedDescription.length > MAX_DESCRIPTION_LENGTH) {
                throw ProductException(ProductErrorCode.DESCRIPTION_TOO_LONG)
            }

            return Product(
                id = null,
                sellerId = sellerId,
                name = normalizedName,
                description = normalizedDescription,
                status = ProductStatus.DRAFT,
                createdAt = createdAt,
            )
        }
    }
}
