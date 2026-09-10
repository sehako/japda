package io.github.sehako.japda.product.infrastructure

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import org.springframework.stereotype.Repository

@Repository
class ProductRepositoryImpl(
	private val productJpaRepository: ProductJpaRepository,
) : ProductRepository {
	override fun save(product: Product): Product = productJpaRepository.save(product)
}
