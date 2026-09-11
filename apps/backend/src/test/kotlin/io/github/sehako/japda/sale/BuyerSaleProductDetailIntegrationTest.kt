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
@Import(BuyerSaleProductDetailIntegrationTest.FixedClockConfig::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구매자 판매 상품 상세 조회 통합")
class BuyerSaleProductDetailIntegrationTest {
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
	@DisplayName("HTTP 상세 조회는 PostgreSQL의 판매 일정과 상품 및 전체 이미지를 반환한다")
	fun HTTP_상세_조회_PostgreSQL의_판매_일정과_상품_및_전체_이미지를_반환한다() {
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, description, status, created_at) VALUES (1, '통합 상품', '통합 설명', 'READY', ?) RETURNING id",
			Long::class.java,
			Timestamp.from(CREATED_AT),
		)!!
		jdbcTemplate.update(
			"INSERT INTO product_images (product_id, object_key, content_type, size_bytes, display_order, is_representative, created_at) VALUES (?, ?, 'image/jpeg', 100, 1, false, ?)",
			productId,
			"products/$productId/request-id/image-b",
			Timestamp.from(CREATED_AT),
		)
		jdbcTemplate.update(
			"INSERT INTO product_images (product_id, object_key, content_type, size_bytes, display_order, is_representative, created_at) VALUES (?, ?, 'image/jpeg', 100, 0, true, ?)",
			productId,
			"products/$productId/request-id/image-a",
			Timestamp.from(CREATED_AT),
		)
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (DATE '2026-09-10', 20, 1)")
		val saleId = jdbcTemplate.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, 1, DATE '2026-09-10', 35000, 100, ?) RETURNING id",
			Long::class.java,
			productId,
			Timestamp.from(CREATED_AT),
		)!!

		mockMvc.perform(get("/api/sales/{saleId}", saleId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.saleId").value(saleId))
			.andExpect(jsonPath("$.productId").value(productId))
			.andExpect(jsonPath("$.name").value("통합 상품"))
			.andExpect(jsonPath("$.description").value("통합 설명"))
			.andExpect(jsonPath("$.price").value(35000))
			.andExpect(jsonPath("$.quantity").value(100))
			.andExpect(jsonPath("$.saleDate").value("2026-09-10"))
			.andExpect(jsonPath("$.startsAt").value("2026-09-09T15:00:00Z"))
			.andExpect(jsonPath("$.endsAt").value("2026-09-10T15:00:00Z"))
			.andExpect(jsonPath("$.status").value("ON_SALE"))
			.andExpect(jsonPath("$.images.length()").value(2))
			.andExpect(jsonPath("$.images[0].path").value("/products/$productId/request-id/image-a"))
			.andExpect(jsonPath("$.images[0].displayOrder").value(0))
			.andExpect(jsonPath("$.images[0].isRepresentative").value(true))
			.andExpect(jsonPath("$.images[1].path").value("/products/$productId/request-id/image-b"))
			.andExpect(jsonPath("$.images[1].displayOrder").value(1))
			.andExpect(jsonPath("$.images[1].isRepresentative").value(false))
	}

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
