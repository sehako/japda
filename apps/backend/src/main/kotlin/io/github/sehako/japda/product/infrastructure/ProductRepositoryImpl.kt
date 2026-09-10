package io.github.sehako.japda.product.infrastructure

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import org.springframework.stereotype.Repository

@Repository
class ProductRepositoryImpl(
	private val productJpaRepository: ProductJpaRepository,
) : ProductRepository {
	override fun save(product: Product): Product = productJpaRepository.save(product)

	override fun findById(id: Long): Product? = productJpaRepository.findById(id).orElse(null)

	override fun findByIdForUpdate(id: Long): Product? = productJpaRepository.findByIdForUpdate(id)
}
