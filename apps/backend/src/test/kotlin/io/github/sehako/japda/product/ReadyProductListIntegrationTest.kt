package io.github.sehako.japda.product

import com.jayway.jsonpath.JsonPath
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import jakarta.servlet.http.Cookie
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.Clock
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(
	properties = [
		"product.image.s3.region=ap-northeast-2",
		"product.image.s3.bucket=test-product-images",
		"spring.security.oauth2.client.registration.google.client-id=synthetic-client-id",
		"spring.security.oauth2.client.registration.google.client-secret=synthetic-client-secret",
	],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매 준비 완료 상품 목록 통합")
class ReadyProductListIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate
	private lateinit var accessToken: String

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		jdbcTemplate.update("DELETE FROM seller_principal_identities WHERE seller_id = 1")
		val userId = jdbcTemplate.queryForObject(
			"INSERT INTO users (provider, provider_subject, email, created_at) VALUES ('GOOGLE', ?, ?, now()) RETURNING id",
			Long::class.java,
			UUID.randomUUID().toString(),
			"seller-${UUID.randomUUID()}@example.com",
		)!!
		jdbcTemplate.update("INSERT INTO seller_principal_identities (user_id, seller_id) VALUES (?, 1)", userId)
		accessToken = ServiceJwtIssuer(
			SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"),
			"http://localhost:8080",
			"japda-spa",
			Clock.systemUTC(),
		).issue(userId)
	}

	@Test
	@DisplayName("HTTP 목록 조회는 판매 일정 존재 여부와 무관하게 본인의 READY 상품만 반환한다")
	fun HTTP_목록_조회_본인의_READY_상품과_판매_일정이_있는_상품만_반환한다() {
		val 일정_없는_READY_상품 = insertProduct(1L, "일정 없는 상품", "READY")
		val 일정_있는_READY_상품 = insertProduct(1L, "일정 있는 상품", "READY")
		insertProduct(1L, "작성 중 상품", "DRAFT")
		insertProduct(2L, "다른 판매자 상품", "READY")
		insertSale(일정_있는_READY_상품, 1L)

		mockMvc.perform(
			get("/api/products/ready")
				.cookie(Cookie("JAPDA_ACCESS_TOKEN", accessToken))
				.queryParam("sort", "latest")
				.queryParam("size", "10"),
		)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].id").value(일정_있는_READY_상품))
			.andExpect(jsonPath("$.items[0].name").value("일정 있는 상품"))
			.andExpect(jsonPath("$.items[1].id").value(일정_없는_READY_상품))
			.andExpect(jsonPath("$.items[1].name").value("일정 없는 상품"))
			.andExpect(jsonPath("$.nextCursor").value(null))
	}

	@Test
	@DisplayName("HTTP 연속 커서 조회는 네 정렬에서 상품을 중복하거나 누락하지 않는다")
	fun HTTP_연속_커서_조회_네_정렬에서_상품을_중복하거나_누락하지_않는다() {
		val 첫째 = insertProduct(1L, "Bravo", "READY")
		val 둘째 = insertProduct(1L, "Alpha", "READY")
		val 셋째 = insertProduct(1L, "Alpha", "READY")
		val 넷째 = insertProduct(1L, "Delta", "READY")
		val 다섯째 = insertProduct(1L, "Charlie", "READY")

		val 기대_순서 = mapOf(
			"latest" to listOf(다섯째, 넷째, 셋째, 둘째, 첫째),
			"oldest" to listOf(첫째, 둘째, 셋째, 넷째, 다섯째),
			"name-asc" to listOf(둘째, 셋째, 첫째, 다섯째, 넷째),
			"name-desc" to listOf(넷째, 다섯째, 첫째, 셋째, 둘째),
		)

		기대_순서.forEach { (sort, expectedIds) ->
			val actualIds = readAllPages(sort)

			assertEquals(expectedIds, actualIds, "$sort 정렬 결과")
			assertEquals(actualIds.size, actualIds.toSet().size, "$sort 페이지에 중복 상품이 없어야 한다")
		}
	}

	private fun readAllPages(sort: String): List<Long> {
		val ids = mutableListOf<Long>()
		var cursor: String? = null

		do {
			val request = get("/api/products/ready")
				.cookie(Cookie("JAPDA_ACCESS_TOKEN", accessToken))
				.queryParam("sort", sort)
				.queryParam("size", "2")
			if (cursor != null) request.queryParam("cursor", cursor)

			val response = mockMvc.perform(request)
				.andExpect(status().isOk)
				.andReturn()
				.response.contentAsString
			val pageIds = JsonPath.read<List<Number>>(response, "$.items[*].id").map(Number::toLong)
			cursor = JsonPath.read<String?>(response, "$.nextCursor")

			assertTrue(pageIds.isNotEmpty(), "$sort 조회 중 빈 중간 페이지를 반환하면 안 된다")
			ids += pageIds
		} while (cursor != null)

		return ids
	}

	private fun insertProduct(sellerId: Long, name: String, status: String): Long = jdbcTemplate.queryForObject(
		"""INSERT INTO products (seller_id, name, description, status, created_at)
			VALUES (?, ?, NULL, ?, ?) RETURNING id""".trimIndent(),
		Long::class.java,
		sellerId,
		name,
		status,
		Timestamp.from(FIXED_INSTANT),
	)!!

	private fun insertSale(productId: Long, sellerId: Long) {
		jdbcTemplate.update(
			"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)",
			Date.valueOf("2026-09-12"),
		)
		jdbcTemplate.update(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, ?, ?, 35000, 100, ?)""".trimIndent(),
			productId,
			sellerId,
			Date.valueOf("2026-09-12"),
			Timestamp.from(FIXED_INSTANT),
		)
	}

	companion object {
		private val FIXED_INSTANT = Instant.parse("2026-09-10T00:00:00Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")

		@DynamicPropertySource
		@JvmStatic
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("auth.jwt-signing-key") { Base64.getEncoder().encodeToString(ByteArray(32) { 7 }) }
		}
	}
}
