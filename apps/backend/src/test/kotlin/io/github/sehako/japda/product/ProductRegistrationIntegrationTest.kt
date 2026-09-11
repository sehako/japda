package io.github.sehako.japda.product

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(
	properties = [
		"product.image.s3.region=ap-northeast-2",
		"product.image.s3.bucket=test-product-images",
	],
)
@AutoConfigureMockMvc
@Import(ProductRegistrationIntegrationTest.FixedClockConfig::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("상품 등록 통합")
class ProductRegistrationIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	@DisplayName("HTTP 상품 등록 요청은 PostgreSQL에 상품을 저장한다")
	fun HTTP_상품_등록_요청_PostgreSQL에_상품을_저장한다() {
		val result = mockMvc.perform(
			post("/api/products")
				.header("X-Seller-Id", "3")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"name":"  통합 상품  ","description":"  통합 설명  "}"""),
		)
			.andExpect(status().isCreated)
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.sellerId").value(3))
			.andExpect(jsonPath("$.name").value("통합 상품"))
			.andExpect(jsonPath("$.description").value("통합 설명"))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.createdAt").value("2026-09-10T00:00:00Z"))
			.andReturn()

		val location = assertNotNull(result.response.getHeader("Location"))
		val productId = location.substringAfterLast('/').toLong()
		val row = jdbcTemplate.queryForMap(
			"SELECT seller_id, name, description, status, created_at FROM products WHERE id = ?",
			productId,
		)

		assertEquals(3L, (row["seller_id"] as Number).toLong())
		assertEquals("통합 상품", row["name"])
		assertEquals("통합 설명", row["description"])
		assertEquals("DRAFT", row["status"])
		assertEquals(Instant.parse("2026-09-10T00:00:00Z"), (row["created_at"] as java.sql.Timestamp).toInstant())
	}

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfig {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(
			Instant.parse("2026-09-10T00:00:00Z"),
			ZoneOffset.UTC,
		)
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
