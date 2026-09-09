package io.github.sehako.japda.product.application.image

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageRegistrationConflictException
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

@DisplayName("상품 이미지 최종 등록 서비스")
class ProductImageRegistrationServiceTest {

	@Test
	@DisplayName("최종 등록_잠금 상품에 이미지 메타데이터를 저장하고 READY로 전환한다")
	fun 최종_등록_잠금_상품에_이미지_메타데이터를_저장하고_READY로_전환한다() {
		val product = product()
		val images = RecordingImageRepository()
		val service = ProductImageRegistrationService(FixedProductRepository(product), images, fixedClock())

		val response = service.register(11L, 7L, registrations())

		assertEquals(ProductStatus.READY, product.status)
		assertEquals(listOf(0, 1), images.saved.map { it.sortOrder })
		assertEquals(listOf(false, true), images.saved.map { it.representative })
		assertEquals(listOf(NOW, NOW), images.saved.map { it.createdAt })
		assertEquals(listOf(201L, 202L), response.images.map { it.id })
	}

	@Test
	@DisplayName("최종 등록_잠금 조회 후 상품 없음과 소유권 불일치를 같은 오류로 거절한다")
	fun 최종_등록_잠금_조회_후_상품_없음과_소유권_불일치를_같은_오류로_거절한다() {
		val missing = ProductImageRegistrationService(FixedProductRepository(null), RecordingImageRepository(), fixedClock())
		val foreign = ProductImageRegistrationService(FixedProductRepository(product(sellerId = 8L)), RecordingImageRepository(), fixedClock())

		assertFailsWith<ProductNotFoundException> { missing.register(11L, 7L, registrations()) }
		assertFailsWith<ProductNotFoundException> { foreign.register(11L, 7L, registrations()) }
	}

	@Test
	@DisplayName("최종 등록_READY 상태 또는 기존 이미지가 있으면 충돌로 거절한다")
	fun 최종_등록_READY_상태_또는_기존_이미지가_있으면_충돌로_거절한다() {
		val readyProduct = product().also { it.markReadyAfterImageRegistration() }
		val ready = ProductImageRegistrationService(FixedProductRepository(readyProduct), RecordingImageRepository(), fixedClock())
		val existing = ProductImageRegistrationService(FixedProductRepository(product()), RecordingImageRepository(exists = true), fixedClock())

		assertFailsWith<ProductImageRegistrationConflictException> { ready.register(11L, 7L, registrations()) }
		assertFailsWith<ProductImageRegistrationConflictException> { existing.register(11L, 7L, registrations()) }
	}

	@Test
	@DisplayName("트랜잭션 경계_S3를 호출하지 않는 최종 등록 메서드에만 선언한다")
	fun 트랜잭션_경계_S3를_호출하지_않는_최종_등록_메서드에만_선언한다() {
		val uploadMethod = ProductImageService::class.java.getDeclaredMethod("upload", UploadProductImagesDto::class.java)
		val registerMethod = ProductImageRegistrationService::class.java.getDeclaredMethod(
			"register",
			Long::class.javaPrimitiveType,
			Long::class.javaPrimitiveType,
			List::class.java,
		)

		assertTrue(ProductImageService::class.java.getAnnotation(Transactional::class.java) == null)
		assertTrue(uploadMethod.getAnnotation(Transactional::class.java) == null)
		assertTrue(registerMethod.getAnnotation(Transactional::class.java) != null)
	}

	private class FixedProductRepository(private val product: Product?) : ProductRepository {
		override fun save(product: Product): Product = product
		override fun findById(id: Long): Product? = product
		override fun findByIdForUpdate(id: Long): Product? = product
	}

	private class RecordingImageRepository(private val exists: Boolean = false) : ProductImageRepository {
		var saved = emptyList<ProductImage>()
			override fun existsByProductId(productId: Long): Boolean = exists

		override fun saveAll(images: List<ProductImage>): List<ProductImage> {
			saved = images
			return images.mapIndexed { index, image ->
				ProductImage(201L + index, image.product, image.objectKey, image.contentType, image.sizeBytes, image.sortOrder, image.representative, image.createdAt)
			}
		}

		override fun findAllByProductIdOrderBySortOrder(productId: Long): List<ProductImage> = saved
	}

	private fun registrations() = listOf(
		ProductImageRegistration("key-1", "image/jpeg", 3L, 0, false),
		ProductImageRegistration("key-2", "image/png", 9L, 1, true),
	)

	private fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

	companion object {
		private val NOW = Instant.parse("2026-09-09T04:00:00Z")

		private fun product(sellerId: Long = 7L) = Product(
			id = 11L,
			sellerId = sellerId,
			name = "상품",
			description = "설명",
			status = ProductStatus.DRAFT,
			createdAt = Instant.parse("2026-09-09T03:00:00Z"),
		)
	}
}
