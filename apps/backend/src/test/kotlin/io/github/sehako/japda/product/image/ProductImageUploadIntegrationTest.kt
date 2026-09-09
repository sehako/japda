package io.github.sehako.japda.product.image

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import io.github.sehako.japda.product.application.image.ProductImageObjectKeyGenerator
import io.github.sehako.japda.product.application.image.ProductImageService
import io.github.sehako.japda.product.application.image.ProductImageUploadFile
import io.github.sehako.japda.product.application.image.UploadProductImagesDto
import io.github.sehako.japda.product.domain.image.ProductImageContent
import io.github.sehako.japda.product.domain.image.ProductImageRegistrationConflictException
import io.github.sehako.japda.product.domain.image.ProductImageStorage
import io.github.sehako.japda.product.domain.image.ProductImageStorageException
import io.github.sehako.japda.product.domain.ProductStatus
import java.io.ByteArrayInputStream
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
	properties = [
		"product.image.s3.bucket=test-private-bucket",
		"product.image.s3.region=ap-northeast-2",
		"product.image.s3.prefix=integration-images",
	],
)
@AutoConfigureMockMvc
@Import(
	PostgreSqlTestContainerConfiguration::class,
	ProductImageUploadIntegrationTest.TestApplicationConfiguration::class,
)
@DisplayName("상품 이미지 업로드 통합")
class ProductImageUploadIntegrationTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbcTemplate: JdbcTemplate,
	@Autowired private val objectMapper: ObjectMapper,
	@Autowired private val storage: InMemoryProductImageStorage,
	@Autowired private val productImageService: ProductImageService,
	@Autowired private val uuidSupplier: ControllableUuidSupplier,
) {

	@BeforeEach
	fun 저장소_대역을_초기화한다() {
		storage.reset()
		uuidSupplier.reset()
	}

	@Test
	@DisplayName("이미지 업로드_기존 API로 만든 DRAFT 상품을 READY와 이미지 메타데이터로 저장한다")
	fun 이미지_업로드_기존_API로_만든_DRAFT_상품을_READY와_이미지_메타데이터로_저장한다() {
		val productId = registerProduct()

		upload(productId).andExpect {
			status { isCreated() }
			jsonPath("$.productId") { value(productId) }
			jsonPath("$.status") { value("READY") }
			jsonPath("$.images[0].sortOrder") { value(0) }
			jsonPath("$.images[0].representative") { value(false) }
			jsonPath("$.images[0].contentType") { value("image/jpeg") }
			jsonPath("$.images[0].sizeBytes") { value(3) }
			jsonPath("$.images[1].sortOrder") { value(1) }
			jsonPath("$.images[1].representative") { value(true) }
			jsonPath("$.images[1].contentType") { value("image/png") }
			jsonPath("$.images[1].sizeBytes") { value(9) }
		}

		assertEquals(ProductStatus.READY, productStatus(productId))
		assertEquals(
			listOf(
				PersistedImage(productId, storage.stored[0].objectKey, "image/jpeg", 3L, 0, false),
				PersistedImage(productId, storage.stored[1].objectKey, "image/png", 9L, 1, true),
			),
			persistedImages(productId),
		)
		assertEquals(listOf("image/jpeg", "image/png"), storage.stored.map { it.contentType })
		assertEquals(listOf(3L, 9L), storage.stored.map { it.sizeBytes })
		assertEquals(listOf(JPEG_BYTES.toList(), PNG_BYTES.toList()), storage.stored.map { it.bytes.toList() })
		assertEquals(storage.stored.map { it.objectKey }.toSet(), storage.objects.keys)
	}

	@Test
	@DisplayName("이미지 업로드_다른 판매자는 상품과 저장소를 변경하지 않는다")
	fun 이미지_업로드_다른_판매자는_상품과_저장소를_변경하지_않는다() {
		val productId = registerProduct()

		upload(productId, sellerId = 999L).andExpect { status { isNotFound() } }

		assertEquals(ProductStatus.DRAFT, productStatus(productId))
		assertTrue(persistedImages(productId).isEmpty())
		assertTrue(storage.stored.isEmpty())
		assertTrue(storage.objects.isEmpty())
		assertTrue(storage.deletedKeys.isEmpty())
	}

	@Test
	@DisplayName("이미지 재업로드_기존 이미지와 READY 상품을 변경하지 않는다")
	fun 이미지_재업로드_기존_이미지와_READY_상품을_변경하지_않는다() {
		val productId = registerProduct()
		upload(productId).andExpect { status { isCreated() } }
		val existingImages = persistedImages(productId)
		storage.reset()

		upload(productId).andExpect { status { isConflict() } }

		assertEquals(ProductStatus.READY, productStatus(productId))
		assertEquals(existingImages, persistedImages(productId))
		assertTrue(storage.stored.isEmpty())
		assertTrue(storage.objects.isEmpty())
		assertTrue(storage.deletedKeys.isEmpty())
	}

	@Test
	@DisplayName("이미지 업로드_두 번째 S3 저장 실패 시 시도한 객체를 역순 보상 삭제하고 DB를 변경하지 않는다")
	fun 이미지_업로드_두_번째_S3_저장_실패_시_시도한_객체를_역순_보상_삭제하고_DB를_변경하지_않는다() {
		val productId = registerProduct()
		storage.failStoreAt = 2

		upload(productId).andExpect { status { isServiceUnavailable() } }

		assertEquals(ProductStatus.DRAFT, productStatus(productId))
		assertTrue(persistedImages(productId).isEmpty())
		assertEquals(1, storage.stored.size)
		assertEquals(storage.storeAttemptKeys.asReversed(), storage.deletedKeys)
		assertTrue(storage.objects.isEmpty())
	}

	@Test
	@DisplayName("동시 최초 업로드_한 요청만 등록하고 패자 객체를 보상 삭제한다")
	fun 동시_최초_업로드_한_요청만_등록하고_패자_객체를_보상_삭제한다() {
		val productId = registerProduct()
		uuidSupplier.enqueue(
			"00000000-0000-0000-0000-000000000001",
			"00000000-0000-0000-0000-000000000002",
			"00000000-0000-0000-0000-000000000003",
			"00000000-0000-0000-0000-000000000004",
		)
		storage.waitAfterStoresPerRequest(storesPerRequest = 2, requestCount = 2)
		val executor = Executors.newFixedThreadPool(2)

		val outcomes = try {
			listOf(
				executor.submit<RuntimeException?> { uploadThroughService(productId) },
				executor.submit<RuntimeException?> { uploadThroughService(productId) },
			).map { it.get(10, TimeUnit.SECONDS) }
		} finally {
			executor.shutdownNow()
		}

		assertEquals(1, outcomes.count { it == null })
		assertIs<ProductImageRegistrationConflictException>(outcomes.single { it != null })
		assertEquals(ProductStatus.READY, productStatus(productId))
		assertEquals(2, persistedImages(productId).size)
		assertEquals(4, storage.stored.size)
		assertEquals(2, storage.deletedKeys.size)
		assertEquals(
			storage.stored.map { it.objectKey }.filter { it in storage.deletedKeys }.asReversed(),
			storage.deletedKeys,
		)
		assertEquals(storage.stored.map { it.objectKey }.toSet() - storage.deletedKeys.toSet(), storage.objects.keys)
		assertEquals(persistedImages(productId).map { it.objectKey }.toSet(), storage.objects.keys)
	}

	@Test
	@DisplayName("최종 DB 반영 실패_PostgreSQL 제약 위반을 rollback하고 요청 객체를 역순 삭제한다")
	fun 최종_DB_반영_실패_PostgreSQL_제약_위반을_rollback하고_요청_객체를_역순_삭제한다() {
		val productId = registerProduct()
		val blockerProductId = registerProduct()
		val firstUuid = "00000000-0000-0000-0000-000000000011"
		val conflictingUuid = "00000000-0000-0000-0000-000000000012"
		val conflictingKey = "integration-images/products/$productId/$conflictingUuid.png"
		jdbcTemplate.update(
			"""
			INSERT INTO product_images
				(product_id, object_key, content_type, size_bytes, sort_order, is_representative, created_at)
			VALUES (?, ?, 'image/jpeg', 3, 0, TRUE, ?)
			""".trimIndent(),
			blockerProductId,
			conflictingKey,
			Timestamp.from(NOW),
		)
		uuidSupplier.enqueue(firstUuid, conflictingUuid)

		assertFailsWith<DataIntegrityViolationException> {
			productImageService.upload(uploadDto(productId))
		}

		val expectedStoredKeys = listOf(
			"integration-images/products/$productId/$firstUuid.jpg",
			conflictingKey,
		)
		assertEquals(ProductStatus.DRAFT, productStatus(productId))
		assertTrue(persistedImages(productId).isEmpty())
		assertEquals(expectedStoredKeys, storage.stored.map { it.objectKey })
		assertEquals(expectedStoredKeys.asReversed(), storage.deletedKeys)
		assertTrue(storage.objects.isEmpty())
	}

	private fun registerProduct(): Long {
		val result = mockMvc.post("/api/products") {
			header("X-Seller-Id", SELLER_ID.toString())
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"통합 상품","description":"통합 설명"}"""
		}.andExpect {
			status { isCreated() }
			jsonPath("$.status") { value("DRAFT") }
		}.andReturn()

		return objectMapper.readTree(result.response.contentAsString).path("id").asLong()
	}

	private fun upload(productId: Long, sellerId: Long = SELLER_ID) =
		mockMvc.multipart("/api/products/$productId/images") {
			file(MockMultipartFile("files", "front.jpg", "image/jpeg", JPEG_BYTES))
			file(MockMultipartFile("files", "detail.png", "image/png", PNG_BYTES))
			param("representativeIndex", "1")
			header("X-Seller-Id", sellerId.toString())
		}

	private fun uploadThroughService(productId: Long): RuntimeException? = try {
		productImageService.upload(uploadDto(productId))
		null
	} catch (exception: RuntimeException) {
		exception
	}

	private fun uploadDto(productId: Long) = UploadProductImagesDto(
		productId = productId,
		sellerId = SELLER_ID,
		files = listOf(
			ProductImageUploadFile("image/jpeg", JPEG_BYTES.size.toLong()) { ByteArrayInputStream(JPEG_BYTES) },
			ProductImageUploadFile("image/png", PNG_BYTES.size.toLong()) { ByteArrayInputStream(PNG_BYTES) },
		),
		representativeIndex = 1,
	)

	private fun productStatus(productId: Long): ProductStatus = ProductStatus.valueOf(
		checkNotNull(jdbcTemplate.queryForObject("SELECT status FROM products WHERE id = ?", String::class.java, productId)),
	)

	private fun persistedImages(productId: Long): List<PersistedImage> = jdbcTemplate.query(
		"""
		SELECT product_id, object_key, content_type, size_bytes, sort_order, is_representative
		FROM product_images
		WHERE product_id = ?
		ORDER BY sort_order
		""".trimIndent(),
		{ resultSet, _ ->
			PersistedImage(
				productId = resultSet.getLong("product_id"),
				objectKey = resultSet.getString("object_key"),
				contentType = resultSet.getString("content_type"),
				sizeBytes = resultSet.getLong("size_bytes"),
				sortOrder = resultSet.getInt("sort_order"),
				representative = resultSet.getBoolean("is_representative"),
			)
		},
		productId,
	)

	private data class PersistedImage(
		val productId: Long,
		val objectKey: String,
		val contentType: String,
		val sizeBytes: Long,
		val sortOrder: Int,
		val representative: Boolean,
	)

	data class StoredObject(
		val objectKey: String,
		val contentType: String,
		val sizeBytes: Long,
		val bytes: ByteArray,
	)

	class InMemoryProductImageStorage : ProductImageStorage {
		val stored = CopyOnWriteArrayList<StoredObject>()
		val storeAttemptKeys = CopyOnWriteArrayList<String>()
		val objects = ConcurrentHashMap<String, StoredObject>()
		val deletedKeys = CopyOnWriteArrayList<String>()
		val cleanupKeys = CopyOnWriteArrayList<String>()
		@Volatile var failStoreAt: Int? = null
		private val storeAttempt = AtomicInteger()
		private val requestStoreCount = ThreadLocal.withInitial { 0 }
		@Volatile private var storesPerRequestBeforeBarrier: Int? = null
		@Volatile private var storeBarrier: CyclicBarrier? = null

		override fun store(objectKey: String, content: ProductImageContent) {
			storeAttemptKeys += objectKey
			if (storeAttempt.incrementAndGet() == failStoreAt) {
				throw ProductImageStorageException(IllegalStateException("테스트 저장 실패"))
			}
			val storedObject = StoredObject(
				objectKey = objectKey,
				contentType = content.contentType,
				sizeBytes = content.sizeBytes,
				bytes = content.openStream().use { it.readBytes() },
			)
			stored += storedObject
			objects[objectKey] = storedObject
			val currentRequestStoreCount = requestStoreCount.get() + 1
			requestStoreCount.set(currentRequestStoreCount)
			if (currentRequestStoreCount == storesPerRequestBeforeBarrier) {
				try {
					storeBarrier?.await(10, TimeUnit.SECONDS)
				} catch (exception: Exception) {
					throw ProductImageStorageException(exception)
				}
			}
		}

		override fun delete(objectKey: String) {
			deletedKeys += objectKey
			objects.remove(objectKey)
		}

		override fun markForCleanup(objectKey: String) {
			cleanupKeys += objectKey
		}

		fun waitAfterStoresPerRequest(storesPerRequest: Int, requestCount: Int) {
			storesPerRequestBeforeBarrier = storesPerRequest
			storeBarrier = CyclicBarrier(requestCount)
		}

		fun reset() {
			stored.clear()
			storeAttemptKeys.clear()
			objects.clear()
			deletedKeys.clear()
			cleanupKeys.clear()
			failStoreAt = null
			storeAttempt.set(0)
			requestStoreCount.remove()
			storesPerRequestBeforeBarrier = null
			storeBarrier = null
		}
	}

	class ControllableUuidSupplier : () -> UUID {
		private val values = ConcurrentLinkedQueue<UUID>()

		override fun invoke(): UUID = values.poll() ?: UUID.randomUUID()

		fun enqueue(vararg uuids: String) {
			uuids.map(UUID::fromString).forEach(values::add)
		}

		fun reset() {
			values.clear()
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	class TestApplicationConfiguration {

		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

		@Bean
		@Primary
		fun inMemoryProductImageStorage(): InMemoryProductImageStorage = InMemoryProductImageStorage()

		@Bean
		fun controllableUuidSupplier(): ControllableUuidSupplier = ControllableUuidSupplier()

		@Bean
		@Primary
		fun controllableProductImageObjectKeyGenerator(
			uuidSupplier: ControllableUuidSupplier,
		): ProductImageObjectKeyGenerator = ProductImageObjectKeyGenerator("integration-images", uuidSupplier)
	}

	companion object {
		private const val SELLER_ID = 123L
		private val NOW = Instant.parse("2026-09-09T05:00:00Z")
		private val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)
		private val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01)
	}
}
