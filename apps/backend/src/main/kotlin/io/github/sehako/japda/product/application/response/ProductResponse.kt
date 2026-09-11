package io.github.sehako.japda.product.application.response

import io.github.sehako.japda.product.domain.model.ProductStatus
import java.time.Instant

data class ProductResponse(
	val id: Long,
	val sellerId: Long,
	val name: String,
	val description: String?,
	val status: ProductStatus,
	val createdAt: Instant,
)
