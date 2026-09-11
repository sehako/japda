package io.github.sehako.japda.product.application.image.service

import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.product.application.image.dto.RegisterProductImagesDto
import io.github.sehako.japda.product.application.image.dto.UploadedProductImage
import io.github.sehako.japda.product.application.image.response.ProductImageRegistrationResponse
import io.github.sehako.japda.product.application.image.response.ProductImageResponse
import io.github.sehako.japda.product.application.image.storage.ProductImageStorage
import io.github.sehako.japda.product.application.image.validation.ProductImageFileValidator
import io.github.sehako.japda.product.domain.model.ProductStatus
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.image.repository.ProductImageRepository
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ProductImageRegistrationService(
	private val productRepository: ProductRepository,
	private val productImageRepository: ProductImageRepository,
	private val storage: ProductImageStorage,
	private val validator: ProductImageFileValidator,
	private val commitService: ProductImageRegistrationCommitService,
	private val clock: Clock,
) {
	fun register(dto: RegisterProductImagesDto): ProductImageRegistrationResponse {
		validateProduct(dto.productId, dto.sellerId)
		val validated = validator.validate(dto.files, dto.representativeIndex)
		val requestId = UUID.randomUUID().toString()
		val uploaded = validated.map { file ->
			UploadedProductImage(
				objectKey = "products/${dto.productId}/$requestId/${UUID.randomUUID()}",
				contentType = file.contentType,
				sizeBytes = file.source.size,
				displayOrder = file.displayOrder,
				isRepresentative = file.isRepresentative,
			)
		}

		try {
			uploaded.zip(validated).forEach { (metadata, file) ->
				file.source.openStream().use { storage.upload(metadata.objectKey, metadata.contentType, metadata.sizeBytes, it) }
			}
		} catch (exception: Exception) {
			compensate(dto.productId, requestId, uploaded.map(UploadedProductImage::objectKey))
			throw exception
		}

		return try {
			commitService.commit(dto.productId, dto.sellerId, uploaded)
		} catch (exception: BusinessException) {
			compensate(dto.productId, requestId, uploaded.map(UploadedProductImage::objectKey))
			throw exception
		} catch (exception: Exception) {
			val referenced = runCatching { productImageRepository.findAllByObjectKeyIn(uploaded.map(UploadedProductImage::objectKey)) }
				.getOrElse {
					logger.error("상품 이미지 커밋 결과 확인 실패: requestId={}, productId={}, keys={}", requestId, dto.productId, uploaded.map(UploadedProductImage::objectKey), it)
					throw exception
				}
			if (referenced.size == uploaded.size) {
				ProductImageRegistrationResponse(dto.productId, ProductStatus.READY, referenced.sortedBy { it.displayOrder }.map {
					ProductImageResponse(requireNotNull(it.id), it.displayOrder, it.isRepresentative)
				})
			} else {
				compensate(dto.productId, requestId, uploaded.map(UploadedProductImage::objectKey))
				throw exception
			}
		}
	}

	private fun validateProduct(productId: Long, sellerId: Long) {
		if (productId <= 0) throw ProductException(ProductErrorCode.ID_INVALID)
		if (sellerId <= 0) throw ProductException(ProductErrorCode.SELLER_ID_INVALID)
		val product = productRepository.findById(productId) ?: throw ProductException(ProductErrorCode.NOT_FOUND)
		if (product.sellerId != sellerId) throw ProductException(ProductErrorCode.ACCESS_DENIED)
		if (product.status != ProductStatus.DRAFT) throw ProductException(ProductErrorCode.IMAGES_ALREADY_REGISTERED)
	}

	private fun compensate(productId: Long, requestId: String, keys: List<String>) {
		keys.forEach { key ->
			runCatching { storage.delete(key) }.onFailure {
				logger.error("상품 이미지 보상 삭제 실패: requestId={}, productId={}, objectKey={}", requestId, productId, key, it)
			}
		}
	}

	companion object {
		private val logger = LoggerFactory.getLogger(ProductImageRegistrationService::class.java)
	}
}
