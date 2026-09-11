package io.github.sehako.japda.product.infrastructure.image.persistence

import io.github.sehako.japda.product.domain.image.model.ProductImage
import org.springframework.data.jpa.repository.JpaRepository

interface ProductImageJpaRepository : JpaRepository<ProductImage, Long> {
	fun findAllByObjectKeyIn(objectKeys: Collection<String>): List<ProductImage>
}
