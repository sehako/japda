package io.github.sehako.japda.product.application.image

import io.github.sehako.japda.product.domain.image.ProductImageContent
import io.github.sehako.japda.product.domain.image.ProductImageRegistrationConflictException
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import io.github.sehako.japda.product.domain.image.ProductImageStorage
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ProductImageService(
	private val productRepository: ProductRepository,
	private val productImageRepository: ProductImageRepository,
	private val fileValidator: ProductImageFileValidator,
	private val objectKeyGenerator: ProductImageObjectKeyGenerator,
	private val storage: ProductImageStorage,
	private val registrationService: ProductImageRegistrationService,
) {

	fun upload(dto: UploadProductImagesDto): ProductImageUploadResponse {
		val validatedFiles = fileValidator.validate(dto.files, dto.representativeIndex)
		validateInitialProduct(dto.productId, dto.sellerId)

		val registrations = validatedFiles.mapIndexed { index, file ->
			ProductImageRegistration(
				objectKey = objectKeyGenerator.generate(dto.productId, file.extension),
				contentType = file.contentType,
				sizeBytes = file.sizeBytes,
				sortOrder = index,
				representative = index == dto.representativeIndex,
			)
		}
		val storedKeys = mutableListOf<String>()

		try {
			registrations.forEachIndexed { index, registration ->
				val file = validatedFiles[index]
				storedKeys += registration.objectKey
				storage.store(
					registration.objectKey,
					ProductImageContent(file.contentType, file.sizeBytes, file.openStream),
				)
			}
			return registrationService.register(dto.productId, dto.sellerId, registrations)
		} catch (failure: RuntimeException) {
			compensate(storedKeys)
			throw failure
		}
	}

	private fun validateInitialProduct(productId: Long, sellerId: Long) {
		val product = productRepository.findById(productId)
			?.takeIf { it.sellerId == sellerId }
			?: throw ProductNotFoundException()
		if (product.status != ProductStatus.DRAFT || productImageRepository.existsByProductId(productId)) {
			throw ProductImageRegistrationConflictException()
		}
	}

	private fun compensate(storedKeys: List<String>) {
		storedKeys.asReversed().forEach { objectKey ->
			try {
				storage.delete(objectKey)
			} catch (_: RuntimeException) {
				logger.warn("상품 이미지 보상 삭제에 실패했습니다. cleanup 표시를 시도합니다. objectKey={}", objectKey)
				try {
					storage.markForCleanup(objectKey)
				} catch (_: RuntimeException) {
					logger.error("상품 이미지 cleanup 표시에 실패했습니다. objectKey={}", objectKey)
				}
			}
		}
	}

	companion object {
		private val logger = LoggerFactory.getLogger(ProductImageService::class.java)
	}
}
