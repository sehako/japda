package io.github.sehako.japda.product.infrastructure.image.persistence

import io.github.sehako.japda.product.domain.image.model.ProductImage
import io.github.sehako.japda.product.domain.image.repository.ProductImageRepository
import org.springframework.stereotype.Repository

@Repository
class ProductImageRepositoryImpl(
	private val productImageJpaRepository: ProductImageJpaRepository,
) : ProductImageRepository {
	override fun saveAll(images: List<ProductImage>): List<ProductImage> =
		productImageJpaRepository.saveAll(images)

	override fun findAllByObjectKeyIn(keys: Collection<String>): List<ProductImage> =
		productImageJpaRepository.findAllByObjectKeyIn(keys)
}
