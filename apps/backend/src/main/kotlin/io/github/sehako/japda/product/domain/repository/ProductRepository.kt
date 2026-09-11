package io.github.sehako.japda.product.domain.repository

import io.github.sehako.japda.product.domain.model.Product

interface ProductRepository {
	fun save(product: Product): Product

	fun findById(id: Long): Product?

	fun findByIdForUpdate(id: Long): Product?

	fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary>
}
