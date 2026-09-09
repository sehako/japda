package io.github.sehako.japda.product.domain.image

interface ProductImageRepository {

	fun existsByProductId(productId: Long): Boolean

	fun saveAll(images: List<ProductImage>): List<ProductImage>

	fun findAllByProductIdOrderBySortOrder(productId: Long): List<ProductImage>
}
