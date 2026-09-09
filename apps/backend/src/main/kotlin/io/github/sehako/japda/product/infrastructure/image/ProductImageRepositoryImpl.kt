package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import org.springframework.stereotype.Repository

@Repository
class ProductImageRepositoryImpl(
	private val productImageJpaRepository: ProductImageJpaRepository,
) : ProductImageRepository {

	override fun existsByProductId(productId: Long): Boolean =
		productImageJpaRepository.existsByProductId(productId)

	override fun saveAll(images: List<ProductImage>): List<ProductImage> =
		productImageJpaRepository.saveAll(images)

	override fun findAllByProductIdOrderBySortOrder(productId: Long): List<ProductImage> =
		productImageJpaRepository.findAllByProductIdOrderBySortOrder(productId)
}
