package io.github.sehako.japda.product.application

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import java.time.Clock
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
			createdAt = clock.instant(),
		)

		return productRepository.save(product).toResponse()
	}

	private fun Product.toResponse(): ProductResponse = ProductResponse(
		id = requireNotNull(id),
		sellerId = sellerId,
		name = name,
		description = description,
		status = status,
		createdAt = createdAt,
	)
}
