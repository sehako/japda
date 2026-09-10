package io.github.sehako.japda.product.domain.image

interface ProductImageRepository {
	fun saveAll(images: List<ProductImage>): List<ProductImage>

	fun findAllByObjectKeyIn(keys: Collection<String>): List<ProductImage>
}
