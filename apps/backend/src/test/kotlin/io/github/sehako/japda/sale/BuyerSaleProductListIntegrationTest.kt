package io.github.sehako.japda.sale

import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
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
import org.springframework.jdbc.core.JdbcTemplate
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
	],
)
@AutoConfigureMockMvc
@Import(BuyerSaleProductListIntegrationTest.FixedClockConfig::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구매자 판매 상품 목록 조회 통합")
class BuyerSaleProductListIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
	}

	@Test
	@DisplayName("HTTP 조회 요청은 PostgreSQL의 판매 일정과 상품 및 대표 이미지를 반환한다")
	fun HTTP_조회_요청_PostgreSQL의_판매_일정과_상품_및_대표_이미지를_반환한다() {
		val productId = insertProduct()
		jdbcTemplate.update(
			"INSERT INTO product_images (product_id, object_key, content_type, size_bytes, display_order, is_representative, created_at) VALUES (?, ?, 'image/jpeg', 100, 0, true, ?)",
			productId,
			"products/$productId/request-id/object-id",
			Timestamp.from(CREATED_AT),
		)
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (DATE '2026-09-10', 20, 1)")
		val saleId = jdbcTemplate.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, 1, DATE '2026-09-10', 35000, 100, ?) RETURNING id",
			Long::class.java,
			productId,
			Timestamp.from(CREATED_AT),
		)!!

		mockMvc.perform(get("/api/sales").queryParam("saleDate", "2026-09-10"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.sales[0].saleId").value(saleId))
			.andExpect(jsonPath("$.sales[0].productId").value(productId))
			.andExpect(jsonPath("$.sales[0].name").value("통합 상품"))
			.andExpect(jsonPath("$.sales[0].description").value("통합 설명"))
			.andExpect(jsonPath("$.sales[0].price").value(35000))
			.andExpect(jsonPath("$.sales[0].quantity").value(100))
			.andExpect(jsonPath("$.sales[0].startsAt").value("2026-09-09T15:00:00Z"))
			.andExpect(jsonPath("$.sales[0].endsAt").value("2026-09-10T15:00:00Z"))
			.andExpect(jsonPath("$.sales[0].status").value("ON_SALE"))
			.andExpect(jsonPath("$.sales[0].representativeImagePath").value("/products/$productId/request-id/object-id"))
	}

	private fun insertProduct(): Long = jdbcTemplate.queryForObject(
		"INSERT INTO products (seller_id, name, description, status, created_at) VALUES (1, '통합 상품', '통합 설명', 'READY', ?) RETURNING id",
		Long::class.java,
		Timestamp.from(CREATED_AT),
	)!!

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfig {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-09-10T03:00:00Z"), ZoneOffset.UTC)
	}

	companion object {
		private val CREATED_AT = Instant.parse("2026-09-01T00:00:00Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
