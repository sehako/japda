package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.domain.image.ProductImage
import org.springframework.data.jpa.repository.JpaRepository

interface ProductImageJpaRepository : JpaRepository<ProductImage, Long> {

	fun existsByProductId(productId: Long): Boolean

	fun findAllByProductIdOrderBySortOrder(productId: Long): List<ProductImage>
}
