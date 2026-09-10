package io.github.sehako.japda.product

import io.github.sehako.japda.product.application.image.ProductImageFile
import io.github.sehako.japda.product.application.image.ProductImageRegistrationCommitService
import io.github.sehako.japda.product.application.image.ProductImageRegistrationService
import io.github.sehako.japda.product.application.image.ProductImageStorage
import io.github.sehako.japda.product.application.image.RegisterProductImagesDto
import io.github.sehako.japda.product.application.image.UploadedProductImage
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(properties = ["product.image.s3.region=ap-northeast-2", "product.image.s3.bucket=test-product-images"])
@AutoConfigureMockMvc
@Import(ProductImageRegistrationIntegrationTest.TestConfig::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("상품 이미지 등록 통합")
class ProductImageRegistrationIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var commitService: ProductImageRegistrationCommitService

	@Autowired
	private lateinit var registrationService: ProductImageRegistrationService

	@Autowired
	private lateinit var storage: RecordingProductImageStorage

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		storage.reset()
	}

	@Test
	@DisplayName("PostgreSQL 제약 위반 시 이미지와 상품 상태 변경을 모두 롤백한다")
	fun PostgreSQL_저장_실패_이미지와_상품_상태를_롤백한다() {
		val productId = insertDraftProduct()
		val uploaded = listOf(
			UploadedProductImage("products/duplicate", "image/jpeg", 12, 0, true),
			UploadedProductImage("products/duplicate", "image/png", 12, 1, false),
		)

		assertFailsWith<DataIntegrityViolationException> {
			commitService.commit(productId, SELLER_ID, uploaded)
		}

		assertEquals(0, imageCount(productId))
		assertEquals("DRAFT", productStatus(productId))
	}

	@Test
	@DisplayName("HTTP multipart 요청은 이미지 메타데이터와 READY 상품을 PostgreSQL에 저장한다")
	fun HTTP_multipart_요청_이미지와_READY_상품을_PostgreSQL에_저장한다() {
		val productId = insertDraftProduct()
		val jpeg = jpegBytes()
		val png = pngBytes()

		mockMvc.perform(
			multipart("/api/products/{productId}/images", productId)
				.file(MockMultipartFile("files", "first.jpg", MediaType.TEXT_PLAIN_VALUE, jpeg))
				.file(MockMultipartFile("files", "second.png", MediaType.APPLICATION_OCTET_STREAM_VALUE, png))
				.file(MockMultipartFile("representativeIndex", "", MediaType.TEXT_PLAIN_VALUE, "1".encodeToByteArray()))
				.header("X-Seller-Id", SELLER_ID.toString()),
		)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.productId").value(productId))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.images[0].displayOrder").value(0))
			.andExpect(jsonPath("$.images[0].isRepresentative").value(false))
			.andExpect(jsonPath("$.images[1].displayOrder").value(1))
			.andExpect(jsonPath("$.images[1].isRepresentative").value(true))

		val rows = jdbcTemplate.queryForList(
			"""SELECT object_key, content_type, size_bytes, display_order, is_representative, created_at
				FROM product_images WHERE product_id = ? ORDER BY display_order""".trimIndent(),
			productId,
		)
		assertEquals("READY", productStatus(productId))
		assertEquals(2, rows.size)
		assertEquals(listOf("image/jpeg", "image/png"), rows.map { it["content_type"] })
		assertEquals(listOf(jpeg.size.toLong(), png.size.toLong()), rows.map { (it["size_bytes"] as Number).toLong() })
		assertEquals(listOf(0, 1), rows.map { (it["display_order"] as Number).toInt() })
		assertEquals(listOf(false, true), rows.map { it["is_representative"] })
		assertTrue(rows.all { (it["created_at"] as java.sql.Timestamp).toInstant() == FIXED_INSTANT })
		assertEquals(rows.map { it["object_key"] }.toSet(), storage.uploadedKeys.toSet())
		assertEquals(emptyList(), storage.deletedKeys.toList())
	}

	@Test
	@DisplayName("동시 등록 중 패자 요청 객체만 보상 삭제하고 승자 객체는 보존한다")
	fun 동시_등록_패자_객체만_삭제하고_승자_객체는_보존한다() {
		val productId = insertDraftProduct()
		storage.waitForUploads(2)
		val dto = RegisterProductImagesDto(productId, SELLER_ID, listOf(file(jpegBytes())), 0)

		val outcomes = Executors.newFixedThreadPool(2).use { executor ->
			val futures = List(2) { executor.submit<Any> { registrationService.register(dto) } }
			futures.map { future ->
				try {
					future.get(10, TimeUnit.SECONDS)
				} catch (exception: ExecutionException) {
					exception.cause ?: exception
				}
			}
		}

		assertEquals(1, outcomes.count { it !is Throwable })
		val conflict = assertNotNull(outcomes.filterIsInstance<ProductException>().singleOrNull())
		assertEquals(ProductErrorCode.IMAGES_ALREADY_REGISTERED, conflict.errorCode)
		assertEquals("READY", productStatus(productId))
		assertEquals(1, imageCount(productId))
		val winnerKey = jdbcTemplate.queryForObject(
			"SELECT object_key FROM product_images WHERE product_id = ?",
			String::class.java,
			productId,
		)
		assertNotNull(winnerKey)
		assertEquals(2, storage.uploadedKeys.size)
		assertEquals(1, storage.deletedKeys.size)
		assertTrue(winnerKey in storage.uploadedKeys)
		assertTrue(winnerKey !in storage.deletedKeys)
		assertEquals((storage.uploadedKeys.toSet() - winnerKey), storage.deletedKeys.toSet())
	}

	private fun insertDraftProduct(): Long = jdbcTemplate.queryForObject(
		"""INSERT INTO products (seller_id, name, description, status, created_at)
			VALUES (?, '통합 상품', NULL, 'DRAFT', ?) RETURNING id""".trimIndent(),
		Long::class.java,
		SELLER_ID,
		java.sql.Timestamp.from(FIXED_INSTANT),
	)!!

	private fun imageCount(productId: Long): Int = jdbcTemplate.queryForObject(
		"SELECT count(*) FROM product_images WHERE product_id = ?",
		Int::class.java,
		productId,
	)!!

	private fun productStatus(productId: Long): String = jdbcTemplate.queryForObject(
		"SELECT status FROM products WHERE id = ?",
		String::class.java,
		productId,
	)!!

	private fun file(bytes: ByteArray) = object : ProductImageFile {
		override val size = bytes.size.toLong()
		override fun openStream() = ByteArrayInputStream(bytes)
	}

	private fun jpegBytes() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9)
	private fun pngBytes() = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4)

	@TestConfiguration(proxyBeanMethods = false)
	class TestConfig {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)

		@Bean
		@Primary
		fun recordingProductImageStorage() = RecordingProductImageStorage()
	}

	class RecordingProductImageStorage : ProductImageStorage {
		val uploadedKeys = ConcurrentLinkedQueue<String>()
		val deletedKeys = ConcurrentLinkedQueue<String>()
		@Volatile
		private var uploadBarrier: CountDownLatch? = null

		override fun upload(objectKey: String, contentType: String, sizeBytes: Long, inputStream: InputStream) {
			inputStream.readAllBytes()
			uploadedKeys.add(objectKey)
			uploadBarrier?.let { barrier ->
				barrier.countDown()
				check(barrier.await(5, TimeUnit.SECONDS)) { "동시 업로드 대기 시간이 초과되었습니다." }
			}
		}

		override fun delete(objectKey: String) {
			deletedKeys.add(objectKey)
		}

		fun waitForUploads(count: Int) {
			uploadBarrier = CountDownLatch(count)
		}

		fun reset() {
			uploadedKeys.clear()
			deletedKeys.clear()
			uploadBarrier = null
		}
	}

	companion object {
		private const val SELLER_ID = 7L
		private val FIXED_INSTANT = Instant.parse("2026-09-10T00:00:00Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
