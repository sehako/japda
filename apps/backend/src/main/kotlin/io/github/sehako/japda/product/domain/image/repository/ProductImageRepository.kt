package io.github.sehako.japda.product.domain.image.repository

import io.github.sehako.japda.product.domain.image.model.ProductImage

interface ProductImageRepository {
	fun saveAll(images: List<ProductImage>): List<ProductImage>

	fun findAllByObjectKeyIn(keys: Collection<String>): List<ProductImage>
}
