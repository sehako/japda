package io.github.sehako.japda.product.application

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Instant

data class ProductResponse(
	val id: Long,
	val sellerId: Long,
	val name: String,
	val description: String,
	val status: ProductStatus,
	val createdAt: Instant,
) {

	companion object {
		fun from(product: Product): ProductResponse = ProductResponse(
			id = checkNotNull(product.id) { "저장된 상품에는 ID가 있어야 합니다." },
			sellerId = product.sellerId,
			name = product.name,
			description = product.description,
			status = product.status,
			createdAt = product.createdAt,
		)
	}
}
