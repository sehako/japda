package io.github.sehako.japda.product.application.image.service

import io.github.sehako.japda.product.application.image.dto.RegisterProductImagesDto
import io.github.sehako.japda.product.application.image.file.ProductImageFile
import io.github.sehako.japda.product.application.image.storage.ProductImageStorage
import io.github.sehako.japda.product.application.image.validation.ProductImageFileValidator
import io.github.sehako.japda.product.domain.model.Product
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.image.model.ProductImage
import io.github.sehako.japda.product.domain.image.repository.ProductImageRepository
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("상품 이미지 등록 서비스")
class ProductImageRegistrationServiceTest {
	private val now = Instant.parse("2026-09-10T00:00:00Z")

	@Test
	@DisplayName("모든 파일 검증이 끝나기 전에는 저장소를 호출하지 않는다")
	fun 검증_실패_저장소를_호출하지_않는다() {
		val storage = RecordingStorage()
		val service = service(storage)

		assertFailsWith<ProductException> {
			service.register(RegisterProductImagesDto(42, 1, listOf(file(jpeg()), file(byteArrayOf(1))), 0))
		}

		assertEquals(emptyList(), storage.uploadedKeys)
	}

	@Test
	@DisplayName("일부 업로드가 실패하면 이번 요청의 모든 객체 키 삭제를 시도한다")
	fun 일부_업로드_실패_모든_객체_키_삭제를_시도한다() {
		val storage = RecordingStorage(failAtUpload = 1)
		val service = service(storage)

		assertFailsWith<ProductException> {
			service.register(RegisterProductImagesDto(42, 1, listOf(file(jpeg()), file(jpeg())), 0))
		}

		assertEquals(2, storage.deletedKeys.size)
	}

	@Test
	@DisplayName("등록 성공 시 저장된 이미지 식별자와 READY 상태를 표시 순서대로 응답한다")
	fun 등록_성공_READY_응답한다() {
		val response = service(RecordingStorage()).register(
			RegisterProductImagesDto(42, 1, listOf(file(jpeg()), file(png())), 1),
		)

		assertEquals(42, response.productId)
		assertEquals("READY", response.status.name)
		assertEquals(listOf(0, 1), response.images.map { it.displayOrder })
		assertEquals(listOf(false, true), response.images.map { it.isRepresentative })
	}

	private fun service(storage: RecordingStorage): ProductImageRegistrationService {
		val product = Product.create(1, "상품", null, now).withId(42)
		val productRepository = ProductRepositoryFake(product)
		val imageRepository = ProductImageRepositoryFake()
		val commitService = ProductImageRegistrationCommitService(productRepository, imageRepository, Clock.fixed(now, ZoneOffset.UTC))
		return ProductImageRegistrationService(
			productRepository,
			imageRepository,
			storage,
			ProductImageFileValidator(),
			commitService,
			Clock.fixed(now, ZoneOffset.UTC),
		)
	}

	private fun file(bytes: ByteArray) = object : ProductImageFile {
		override val size = bytes.size.toLong()
		override fun openStream() = ByteArrayInputStream(bytes)
	}

	private fun jpeg() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
	private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

	private fun Product.withId(id: Long): Product = apply {
		Product::class.java.getDeclaredField("id").apply { isAccessible = true; set(this@withId, id) }
	}

	private class ProductRepositoryFake(private val product: Product) : ProductRepository {
		override fun save(product: Product) = product
		override fun findById(id: Long) = product.takeIf { it.id == id }
		override fun findByIdForUpdate(id: Long) = findById(id)
		override fun findReadyProducts(query: io.github.sehako.japda.product.domain.repository.ReadyProductQuery) = emptyList<io.github.sehako.japda.product.domain.repository.ReadyProductSummary>()
	}

	private class ProductImageRepositoryFake : ProductImageRepository {
		private val saved = mutableListOf<ProductImage>()
		override fun saveAll(images: List<ProductImage>): List<ProductImage> = images.onEachIndexed { index, image ->
			ProductImage::class.java.getDeclaredField("id").apply { isAccessible = true; set(image, (index + 101).toLong()) }
		}.also(saved::addAll)
		override fun findAllByObjectKeyIn(keys: Collection<String>) = saved.filter { it.objectKey in keys }
	}

	private class RecordingStorage(private val failAtUpload: Int? = null) : ProductImageStorage {
		val uploadedKeys = mutableListOf<String>()
		val deletedKeys = mutableListOf<String>()
		override fun upload(objectKey: String, contentType: String, sizeBytes: Long, inputStream: java.io.InputStream) {
			val index = uploadedKeys.size
			uploadedKeys += objectKey
			if (failAtUpload == index) throw ProductException(ProductErrorCode.IMAGE_STORAGE_FAILED)
		}
		override fun delete(objectKey: String) { deletedKeys += objectKey }
	}
}
