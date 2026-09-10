package io.github.sehako.japda.product.domain

interface ProductRepository {
	fun save(product: Product): Product

	fun findById(id: Long): Product?

	fun findByIdForUpdate(id: Long): Product?
}
