package io.github.sehako.japda.product.application.image

import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageRegistrationConflictException
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ProductImageRegistration(
	val objectKey: String,
	val contentType: String,
	val sizeBytes: Long,
	val sortOrder: Int,
	val representative: Boolean,
)

@Service
class ProductImageRegistrationService(
	private val productRepository: ProductRepository,
	private val productImageRepository: ProductImageRepository,
	private val clock: Clock,
) {

	@Transactional
	fun register(
		productId: Long,
		sellerId: Long,
		registrations: List<ProductImageRegistration>,
	): ProductImageUploadResponse {
		val product = productRepository.findByIdForUpdate(productId)
			?.takeIf { it.sellerId == sellerId }
			?: throw ProductNotFoundException()

		if (product.status != ProductStatus.DRAFT || productImageRepository.existsByProductId(productId)) {
			throw ProductImageRegistrationConflictException()
		}

		val createdAt = Instant.now(clock)
		val savedImages = productImageRepository.saveAll(
			registrations.map { registration ->
				ProductImage.create(
					product = product,
					objectKey = registration.objectKey,
					contentType = registration.contentType,
					sizeBytes = registration.sizeBytes,
					sortOrder = registration.sortOrder,
					representative = registration.representative,
					createdAt = createdAt,
				)
			},
		)
		product.markReadyAfterImageRegistration()

		return ProductImageUploadResponse(
			productId = checkNotNull(product.id) { "저장된 상품에는 ID가 있어야 합니다." },
			status = product.status,
			images = savedImages.sortedBy { it.sortOrder }.map(ProductImageResponse::from),
		)
	}
}
