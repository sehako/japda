package io.github.sehako.japda.sale

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import io.github.sehako.japda.product.domain.image.ProductImageContent
import io.github.sehako.japda.product.domain.image.ProductImageStorage
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
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
		"product.image.s3.prefix=sale-integration-images",
	],
)
@AutoConfigureMockMvc
@Import(
	PostgreSqlTestContainerConfiguration::class,
	SaleRegistrationIntegrationTest.TestApplicationConfiguration::class,
)
@DisplayName("판매 등록 통합")
class SaleRegistrationIntegrationTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbcTemplate: JdbcTemplate,
	@Autowired private val objectMapper: ObjectMapper,
) {

	@Test
	@DisplayName("판매 등록_DRAFT 상품이면 판매를 저장하지 않고 409를 반환한다")
	fun 판매_등록_DRAFT_상품이면_판매를_저장하지_않고_409를_반환한다() {
		val productId = registerProduct()

		registerSale(productId).andExpect { status { isConflict() } }

		assertEquals(0L, saleCount(productId))
		assertEquals(ProductStatus.DRAFT, productStatus(productId))
	}

	@Test
	@DisplayName("판매 등록_이미지 등록으로 READY가 된 상품에 판매를 저장하고 201을 반환한다")
	fun 판매_등록_이미지_등록으로_READY가_된_상품에_판매를_저장하고_201을_반환한다() {
		val productId = registerReadyProduct()

		val result = registerSale(productId).andExpect {
			status { isCreated() }
			jsonPath("$.productId") { value(productId) }
			jsonPath("$.price") { value(10_000) }
			jsonPath("$.initialQuantity") { value(100) }
			jsonPath("$.remainingQuantity") { value(100) }
			jsonPath("$.startsAt") { value(STARTS_AT.toString()) }
			jsonPath("$.endsAt") { value(ENDS_AT.toString()) }
			jsonPath("$.status") { value("SCHEDULED") }
			jsonPath("$.createdAt") { value(NOW.toString()) }
		}.andReturn()
		val saleId = objectMapper.readTree(result.response.contentAsString).path("id").asLong()

		assertEquals("/api/sales/$saleId", result.response.getHeader("Location"))
		assertEquals(1L, saleCount(productId))
		assertEquals(ProductStatus.READY, productStatus(productId))
	}

	@Test
	@DisplayName("판매 기간_동일 요청과 중첩 기간은 409이고 종료 경계에 인접한 기간은 201이다")
	fun 판매_기간_동일_요청과_중첩_기간은_409이고_종료_경계에_인접한_기간은_201이다() {
		val productId = registerReadyProduct()
		registerSale(productId).andExpect { status { isCreated() } }

		registerSale(productId).andExpect { status { isConflict() } }
		registerSale(
			productId = productId,
			startsAt = Instant.parse("2026-09-10T09:00:00Z"),
			endsAt = Instant.parse("2026-09-17T09:00:00Z"),
		).andExpect { status { isConflict() } }
		registerSale(
			productId = productId,
			startsAt = ENDS_AT,
			endsAt = Instant.parse("2026-09-23T09:00:00Z"),
		).andExpect { status { isCreated() } }

		assertEquals(2L, saleCount(productId))
	}

	@Test
	@DisplayName("판매 등록_다른 판매자이면 상품 존재를 노출하지 않고 404를 반환한다")
	fun 판매_등록_다른_판매자이면_상품_존재를_노출하지_않고_404를_반환한다() {
		val productId = registerReadyProduct()

		registerSale(productId, sellerId = OTHER_SELLER_ID).andExpect { status { isNotFound() } }

		assertEquals(0L, saleCount(productId))
	}

	@Test
	@DisplayName("이미지와 판매 등록 경합_READY가 아닌 상품에는 판매를 저장하지 않는다")
	fun 이미지와_판매_등록_경합_READY가_아닌_상품에는_판매를_저장하지_않는다() {
		val productId = registerProduct()

		val outcomes = executeConcurrently(
			{ uploadImages(productId).andReturn().response.status },
			{ registerSale(productId).andReturn().response.status },
		)
		val imageStatus = outcomes[0]
		val saleStatus = outcomes[1]

		assertEquals(HttpStatus.CREATED.value(), imageStatus)
		assertTrue(
			saleStatus == HttpStatus.CREATED.value() || saleStatus == HttpStatus.CONFLICT.value(),
			"판매 요청은 이미지 등록 커밋 이후 성공하거나 DRAFT를 관찰해 충돌해야 합니다.",
		)
		assertEquals(ProductStatus.READY, productStatus(productId))
		assertEquals(if (saleStatus == HttpStatus.CREATED.value()) 1L else 0L, saleCount(productId))
	}

	@Test
	@DisplayName("동시 중첩 판매 등록_정확히 한 요청만 성공하고 판매 한 건만 저장한다")
	fun 동시_중첩_판매_등록_정확히_한_요청만_성공하고_판매_한_건만_저장한다() {
		val productId = registerReadyProduct()

		val outcomes = executeConcurrently(
			{ registerSale(productId).andReturn().response.status },
			{
				registerSale(
					productId = productId,
					startsAt = Instant.parse("2026-09-10T09:00:00Z"),
					endsAt = Instant.parse("2026-09-17T09:00:00Z"),
				).andReturn().response.status
			},
		)

		assertEquals(
			listOf(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value()),
			outcomes.sorted(),
		)
		assertEquals(1L, saleCount(productId))
	}

	private fun registerReadyProduct(sellerId: Long = SELLER_ID): Long {
		val productId = registerProduct(sellerId)
		uploadImages(productId, sellerId).andExpect {
			status { isCreated() }
			jsonPath("$.status") { value("READY") }
		}
		return productId
	}

	private fun registerProduct(sellerId: Long = SELLER_ID): Long {
		val result = mockMvc.post("/api/products") {
			header("X-Seller-Id", sellerId.toString())
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"판매 통합 상품","description":"판매 통합 설명"}"""
		}.andExpect {
			status { isCreated() }
			jsonPath("$.status") { value("DRAFT") }
		}.andReturn()

		return objectMapper.readTree(result.response.contentAsString).path("id").asLong()
	}

	private fun uploadImages(productId: Long, sellerId: Long = SELLER_ID) =
		mockMvc.multipart("/api/products/$productId/images") {
			file(MockMultipartFile("files", "front.jpg", "image/jpeg", JPEG_BYTES))
			param("representativeIndex", "0")
			header("X-Seller-Id", sellerId.toString())
		}

	private fun registerSale(
		productId: Long,
		sellerId: Long = SELLER_ID,
		startsAt: Instant = STARTS_AT,
		endsAt: Instant = ENDS_AT,
	) = mockMvc.post("/api/products/$productId/sales") {
		header("X-Seller-Id", sellerId.toString())
		contentType = MediaType.APPLICATION_JSON
		content =
			"""{"price":10000,"quantity":100,"startsAt":"$startsAt","endsAt":"$endsAt"}"""
	}

	private fun executeConcurrently(vararg requests: () -> Int): List<Int> {
		val barrier = CyclicBarrier(requests.size)
		val executor = Executors.newFixedThreadPool(requests.size)
		return try {
			requests.map { request ->
				executor.submit<Int> {
					barrier.await(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					request()
				}
			}.map { it.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
		} finally {
			executor.shutdownNow()
		}
	}

	private fun productStatus(productId: Long): ProductStatus = ProductStatus.valueOf(
		checkNotNull(jdbcTemplate.queryForObject("SELECT status FROM products WHERE id = ?", String::class.java, productId)),
	)

	private fun saleCount(productId: Long): Long = checkNotNull(
		jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sales WHERE product_id = ?", Long::class.java, productId),
	)

	private class InMemoryProductImageStorage : ProductImageStorage {
		override fun store(objectKey: String, content: ProductImageContent) = Unit

		override fun delete(objectKey: String) = Unit

		override fun markForCleanup(objectKey: String) = Unit
	}

	@TestConfiguration(proxyBeanMethods = false)
	class TestApplicationConfiguration {

		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

		@Bean
		@Primary
		fun inMemoryProductImageStorage(): ProductImageStorage = InMemoryProductImageStorage()
	}

	companion object {
		private const val SELLER_ID = 123L
		private const val OTHER_SELLER_ID = 999L
		private const val CONCURRENT_TIMEOUT_SECONDS = 15L
		private val NOW = Instant.parse("2026-09-09T05:00:00Z")
		private val STARTS_AT = Instant.parse("2026-09-09T09:00:00Z")
		private val ENDS_AT = Instant.parse("2026-09-16T09:00:00Z")
		private val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)
	}
}
