package io.github.sehako.japda.product.application.image

import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProductImageRegistrationCommitService(
	private val productRepository: ProductRepository,
	private val productImageRepository: ProductImageRepository,
	private val clock: Clock,
) {
	@Transactional
	fun commit(productId: Long, sellerId: Long, uploaded: List<UploadedProductImage>): ProductImageRegistrationResponse {
		val product = productRepository.findByIdForUpdate(productId)
			?: throw ProductException(ProductErrorCode.NOT_FOUND)
		if (product.sellerId != sellerId) throw ProductException(ProductErrorCode.ACCESS_DENIED)

		val images = uploaded.map {
			ProductImage.create(productId, it.objectKey, it.contentType, it.sizeBytes, it.displayOrder, it.isRepresentative, clock.instant())
		}
		ProductImage.validateRegistration(images)
		product.markReady()
		val saved = productImageRepository.saveAll(images)
		return ProductImageRegistrationResponse(
			productId,
			product.status,
			saved.sortedBy(ProductImage::displayOrder).map {
				ProductImageResponse(requireNotNull(it.id), it.displayOrder, it.isRepresentative)
			},
		)
	}
}

data class UploadedProductImage(
	val objectKey: String,
	val contentType: String,
	val sizeBytes: Long,
	val displayOrder: Int,
	val isRepresentative: Boolean,
)
