package io.github.sehako.japda.product

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(
	PostgreSqlTestContainerConfiguration::class,
	ProductRegistrationIntegrationTest.FixedClockConfiguration::class,
)
@DisplayName("상품 등록 통합")
class ProductRegistrationIntegrationTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbcTemplate: JdbcTemplate,
	@Autowired private val objectMapper: ObjectMapper,
) {

	@Test
	@DisplayName("상품 등록_HTTP 요청을 정규화해 서로 다른 ID의 두 상품으로 저장한다")
	fun 상품_등록_HTTP_요청을_정규화해_서로_다른_ID의_두_상품으로_저장한다() {
		val firstResult = registerProduct()
		val secondResult = registerProduct()
		val firstId = objectMapper.readTree(firstResult.response.contentAsString).path("id").asLong()
		val secondId = objectMapper.readTree(secondResult.response.contentAsString).path("id").asLong()

		val products = jdbcTemplate.query(
			"""
			SELECT id, seller_id, name, description, status, created_at
			FROM products
			WHERE id IN (?, ?)
			ORDER BY id
			""".trimIndent(),
			{ resultSet, _ ->
				PersistedProduct(
					id = resultSet.getLong("id"),
					sellerId = resultSet.getLong("seller_id"),
					name = resultSet.getString("name"),
					description = resultSet.getString("description"),
					status = ProductStatus.valueOf(resultSet.getString("status")),
					createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
				)
			},
			firstId,
			secondId,
		)

		assertNotEquals(firstId, secondId)
		assertEquals("/api/products/$firstId", firstResult.response.getHeader("Location"))
		assertEquals("/api/products/$secondId", secondResult.response.getHeader("Location"))
		assertEquals(
			listOf(
				PersistedProduct(firstId, 123L, "한정판  상품", "상품  설명", ProductStatus.DRAFT, NOW),
				PersistedProduct(secondId, 123L, "한정판  상품", "상품  설명", ProductStatus.DRAFT, NOW),
			),
			products,
		)
	}

	private fun registerProduct() = mockMvc.post("/api/products") {
		header("X-Seller-Id", "123")
		contentType = MediaType.APPLICATION_JSON
		content = """{"name":"\u00A0한정판  상품\u3000","description":"\u2003상품  설명\u00A0"}"""
	}.andExpect {
		status { isCreated() }
		jsonPath("$.name") { value("한정판  상품") }
		jsonPath("$.description") { value("상품  설명") }
		jsonPath("$.status") { value("DRAFT") }
		jsonPath("$.createdAt") { value("2026-09-09T03:00:00Z") }
	}.andReturn()

	private data class PersistedProduct(
		val id: Long,
		val sellerId: Long,
		val name: String,
		val description: String,
		val status: ProductStatus,
		val createdAt: Instant,
	)

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfiguration {

		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
	}

	companion object {
		private val NOW = Instant.parse("2026-09-09T03:00:00Z")
	}
}
