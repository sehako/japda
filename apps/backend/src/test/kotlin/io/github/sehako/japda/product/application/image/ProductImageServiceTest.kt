package io.github.sehako.japda.product.application.image

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageContent
import io.github.sehako.japda.product.domain.image.ProductImageRegistrationConflictException
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import io.github.sehako.japda.product.domain.image.ProductImageStorage
import io.github.sehako.japda.product.domain.image.ProductImageStorageException
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

@DisplayName("상품 이미지 서비스")
class ProductImageServiceTest {

	@Test
	@DisplayName("이미지 업로드_검증된 파일을 순서대로 저장하고 등록 결과를 반환한다")
	fun 이미지_업로드_검증된_파일을_순서대로_저장하고_등록_결과를_반환한다() {
		val fixture = fixture()

		val response = fixture.service.upload(dto())

		assertEquals(
			listOf(
				"images/products/11/00000000-0000-0000-0000-000000000001.jpg",
				"images/products/11/00000000-0000-0000-0000-000000000002.png",
			),
			fixture.storage.storedKeys,
		)
		assertEquals(11L, response.productId)
		assertEquals(ProductStatus.READY, response.status)
		assertEquals(listOf(0, 1), response.images.map { it.sortOrder })
		assertEquals(listOf(false, true), response.images.map { it.representative })
		assertEquals(listOf("image/jpeg", "image/png"), response.images.map { it.contentType })
		assertEquals(listOf(3L, 9L), response.images.map { it.sizeBytes })
	}

	@Test
	@DisplayName("이미지 업로드_상품이 없거나 다른 판매자 소유이면 같은 오류로 거절한다")
	fun 이미지_업로드_상품이_없거나_다른_판매자_소유이면_같은_오류로_거절한다() {
		val missing = fixture(foundProduct = null)
		val foreign = fixture(foundProduct = product(sellerId = 99L))

		assertFailsWith<ProductNotFoundException> { missing.service.upload(dto()) }
		assertFailsWith<ProductNotFoundException> { foreign.service.upload(dto()) }
		assertTrue(missing.storage.storedKeys.isEmpty())
		assertTrue(foreign.storage.storedKeys.isEmpty())
	}

	@Test
	@DisplayName("이미지 업로드_READY 상품 또는 기존 이미지가 있으면 저장 전에 충돌로 거절한다")
	fun 이미지_업로드_READY_상품_또는_기존_이미지가_있으면_저장_전에_충돌로_거절한다() {
		val readyProduct = product().also { it.markReadyAfterImageRegistration() }
		val ready = fixture(foundProduct = readyProduct)
		val existing = fixture(imagesExist = true)

		assertFailsWith<ProductImageRegistrationConflictException> { ready.service.upload(dto()) }
		assertFailsWith<ProductImageRegistrationConflictException> { existing.service.upload(dto()) }
		assertTrue(ready.storage.storedKeys.isEmpty())
		assertTrue(existing.storage.storedKeys.isEmpty())
	}

	@Test
	@DisplayName("이미지 업로드_중간 저장 실패 시 시도한 객체를 역순으로 삭제하고 원래 오류를 보존한다")
	fun 이미지_업로드_중간_저장_실패_시_시도한_객체를_역순으로_삭제하고_원래_오류를_보존한다() {
		val failure = ProductImageStorageException(IllegalStateException("S3 내부 오류"))
		val fixture = fixture(storageFailureAt = 2, storageFailure = failure)

		val thrown = assertFailsWith<ProductImageStorageException> {
			fixture.service.upload(dto(fileCount = 3))
		}

		assertTrue(thrown === failure)
		assertEquals(fixture.storage.attemptedKeys.asReversed(), fixture.storage.deletedKeys)
	}

	@Test
	@DisplayName("이미지 업로드_S3 저장 후 응답이 유실되어도 해당 객체를 보상 삭제한다")
	fun 이미지_업로드_S3_저장_후_응답이_유실되어도_해당_객체를_보상_삭제한다() {
		val failure = ProductImageStorageException(IllegalStateException("응답 유실"))
		val fixture = fixture(storageFailureAfterStoreAt = 1, storageFailure = failure)

		val thrown = assertFailsWith<ProductImageStorageException> {
			fixture.service.upload(dto(fileCount = 1))
		}

		assertTrue(thrown === failure)
		assertEquals(fixture.storage.storedKeys, fixture.storage.deletedKeys)
	}

	@Test
	@DisplayName("이미지 업로드_DB 반영 실패 시 저장한 모든 객체를 역순으로 삭제한다")
	fun 이미지_업로드_DB_반영_실패_시_저장한_모든_객체를_역순으로_삭제한다() {
		val failure = IllegalStateException("DB 오류")
		val fixture = fixture(databaseFailure = failure)

		val thrown = assertFailsWith<IllegalStateException> {
			fixture.service.upload(dto(fileCount = 3))
		}

		assertTrue(thrown === failure)
		assertEquals(fixture.storage.storedKeys.asReversed(), fixture.storage.deletedKeys)
	}

	@Test
	@DisplayName("이미지 업로드_보상 삭제 실패 시 cleanup 표시를 시도하고 원래 오류를 보존한다")
	fun 이미지_업로드_보상_삭제_실패_시_cleanup_표시를_시도하고_원래_오류를_보존한다() {
		val originalFailure = IllegalStateException("DB 오류")
		val fixture = fixture(databaseFailure = originalFailure, deleteFailures = setOf(1, 2))

		val thrown = assertFailsWith<IllegalStateException> {
			fixture.service.upload(dto())
		}

		assertTrue(thrown === originalFailure)
		assertEquals(fixture.storage.deletedKeys, fixture.storage.cleanupKeys)
	}

	@Test
	@DisplayName("이미지 업로드_cleanup 표시도 실패하면 원래 오류를 보존한다")
	fun 이미지_업로드_cleanup_표시도_실패하면_원래_오류를_보존한다() {
		val originalFailure = IllegalStateException("DB 오류")
		val fixture = fixture(databaseFailure = originalFailure, deleteFailures = setOf(1), cleanupFailure = true)

		val thrown = assertFailsWith<IllegalStateException> {
			fixture.service.upload(dto(fileCount = 1))
		}

		assertTrue(thrown === originalFailure)
		assertEquals(1, fixture.storage.cleanupKeys.size)
	}

	@Test
	@DisplayName("이미지 업로드_보상 실패 로그에 저장소 내부 오류를 노출하지 않는다")
	fun 이미지_업로드_보상_실패_로그에_저장소_내부_오류를_노출하지_않는다() {
		val logger = LoggerFactory.getLogger(ProductImageService::class.java) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		logger.addAppender(appender)
		try {
			val fixture = fixture(
				databaseFailure = IllegalStateException("DB 오류"),
				deleteFailures = setOf(1),
				cleanupFailure = true,
			)

			assertFailsWith<IllegalStateException> { fixture.service.upload(dto(fileCount = 1)) }

			val renderedLogs = appender.list.joinToString("\n") { event ->
				buildString {
					append(event.formattedMessage)
					event.throwableProxy?.let { append(ThrowableProxyUtil.asString(it)) }
				}
			}
			assertTrue(renderedLogs.contains("상품 이미지 보상 삭제에 실패했습니다."))
			assertTrue(renderedLogs.contains("상품 이미지 cleanup 표시에 실패했습니다."))
			assertTrue(!renderedLogs.contains("민감한 저장소 내부 오류"))
		} finally {
			logger.detachAppender(appender)
		}
	}

	@Test
	@DisplayName("이미지 업로드_최종 잠금 재검사에서 충돌하면 저장 객체를 보상 삭제한다")
	fun 이미지_업로드_최종_잠금_재검사에서_충돌하면_저장_객체를_보상_삭제한다() {
		val lockedReadyProduct = product().also { it.markReadyAfterImageRegistration() }
		val fixture = fixture(lockedProduct = lockedReadyProduct)

		assertFailsWith<ProductImageRegistrationConflictException> {
			fixture.service.upload(dto())
		}
		assertEquals(fixture.storage.storedKeys.asReversed(), fixture.storage.deletedKeys)
	}

	private fun fixture(
		foundProduct: Product? = product(),
		lockedProduct: Product? = foundProduct,
		imagesExist: Boolean = false,
		storageFailureAt: Int? = null,
		storageFailureAfterStoreAt: Int? = null,
		storageFailure: RuntimeException = ProductImageStorageException(IllegalStateException()),
		databaseFailure: RuntimeException? = null,
		deleteFailures: Set<Int> = emptySet(),
		cleanupFailure: Boolean = false,
	): Fixture {
		val productRepository = FakeProductRepository(foundProduct, lockedProduct)
		val imageRepository = FakeProductImageRepository(imagesExist, databaseFailure)
		val storage = RecordingStorage(
			storageFailureAt,
			storageFailureAfterStoreAt,
			storageFailure,
			deleteFailures,
			cleanupFailure,
		)
		val clock = Clock.fixed(NOW, ZoneOffset.UTC)
		val registrationService = ProductImageRegistrationService(productRepository, imageRepository, clock)
		val uuids = ArrayDeque(
			(1..10).map { UUID.fromString("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}") },
		)
		val service = ProductImageService(
			productRepository,
			imageRepository,
			ProductImageFileValidator(),
			ProductImageObjectKeyGenerator("images") { uuids.removeFirst() },
			storage,
			registrationService,
		)
		return Fixture(service, storage)
	}

	private fun dto(fileCount: Int = 2): UploadProductImagesDto {
		val files = listOf(
			ProductImageUploadFile("image/jpeg", 3) { ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)) },
			ProductImageUploadFile("image/png", 9) {
				ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1))
			},
			ProductImageUploadFile("image/jpeg", 3) { ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 2)) },
		).take(fileCount)
		return UploadProductImagesDto(11L, 7L, files, representativeIndex = minOf(1, fileCount - 1))
	}

	private data class Fixture(val service: ProductImageService, val storage: RecordingStorage)

	private class FakeProductRepository(
		private val foundProduct: Product?,
		private val lockedProduct: Product?,
	) : ProductRepository {
		override fun save(product: Product): Product = product
		override fun findById(id: Long): Product? = foundProduct
		override fun findByIdForUpdate(id: Long): Product? = lockedProduct
	}

	private class FakeProductImageRepository(
		private val imagesExist: Boolean,
		private val databaseFailure: RuntimeException?,
	) : ProductImageRepository {
		private var savedImages = emptyList<ProductImage>()

		override fun existsByProductId(productId: Long): Boolean = imagesExist || savedImages.isNotEmpty()

		override fun saveAll(images: List<ProductImage>): List<ProductImage> {
			databaseFailure?.let { throw it }
			savedImages = images.mapIndexed { index, image ->
				ProductImage(
					id = 101L + index,
					product = image.product,
					objectKey = image.objectKey,
					contentType = image.contentType,
					sizeBytes = image.sizeBytes,
					sortOrder = image.sortOrder,
					representative = image.representative,
					createdAt = image.createdAt,
				)
			}
			return savedImages
		}

		override fun findAllByProductIdOrderBySortOrder(productId: Long): List<ProductImage> =
			savedImages.sortedBy { it.sortOrder }
	}

	private class RecordingStorage(
		private val failureAt: Int?,
		private val failureAfterStoreAt: Int?,
		private val storageFailure: RuntimeException,
		private val deleteFailures: Set<Int>,
		private val cleanupFailure: Boolean,
	) : ProductImageStorage {
		val storedKeys = mutableListOf<String>()
		val attemptedKeys = mutableListOf<String>()
		val deletedKeys = mutableListOf<String>()
		val cleanupKeys = mutableListOf<String>()
		private var storeAttempt = 0
		private var deleteAttempt = 0

		override fun store(objectKey: String, content: ProductImageContent) {
			storeAttempt++
			attemptedKeys += objectKey
			if (storeAttempt == failureAt) throw storageFailure
			content.openStream().use { it.readBytes() }
			storedKeys += objectKey
			if (storeAttempt == failureAfterStoreAt) throw storageFailure
		}

		override fun delete(objectKey: String) {
			deleteAttempt++
			deletedKeys += objectKey
			if (deleteAttempt in deleteFailures) {
				throw ProductImageStorageException(IllegalStateException("민감한 저장소 내부 오류"))
			}
		}

		override fun markForCleanup(objectKey: String) {
			cleanupKeys += objectKey
			if (cleanupFailure) {
				throw ProductImageStorageException(IllegalStateException("민감한 저장소 내부 오류"))
			}
		}
	}

	companion object {
		private val NOW = Instant.parse("2026-09-09T04:00:00Z")

		private fun product(sellerId: Long = 7L): Product = Product(
			id = 11L,
			sellerId = sellerId,
			name = "상품",
			description = "설명",
			status = ProductStatus.DRAFT,
			createdAt = Instant.parse("2026-09-09T03:00:00Z"),
		)
	}
}
