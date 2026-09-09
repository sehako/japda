package io.github.sehako.japda.product.application

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProductService(
	private val productRepository: ProductRepository,
	private val clock: Clock,
) {

	@Transactional
	fun create(dto: CreateProductDto): ProductResponse {
		val product = Product.create(
			sellerId = dto.sellerId,
			name = dto.name,
			description = dto.description,
			createdAt = Instant.now(clock),
		)

		return ProductResponse.from(productRepository.save(product))
	}
}
