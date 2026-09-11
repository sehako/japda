package io.github.sehako.japda.sale

import io.github.sehako.japda.BackendApplication
import io.github.sehako.japda.sale.application.dto.CreateSaleDto
import io.github.sehako.japda.sale.application.service.SaleService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매일 정원 애플리케이션 컨텍스트 통합")
class SaleDailyCapacityContextIntegrationTest {
	@Test
	@DisplayName("설정값이 다른 새 컨텍스트는 기존 판매일 정원을 유지하고 새 판매일에 변경된 정원을 적용한다")
	fun 다른_컨텍스트_기존_판매일_정원_유지_새_판매일_변경_정원_적용한다() {
		applicationContext(capacity = 3, now = "2026-09-11T00:00:01Z").use { first ->
			val jdbcTemplate = first.getBean(JdbcTemplate::class.java)
			clear(jdbcTemplate)
			val productId = insertReadyProduct(jdbcTemplate, sellerId = 1L)
			first.getBean(SaleService::class.java).create(
				CreateSaleDto(1L, productId, LocalDate.parse("2026-09-12"), 35_000L, 100),
			)
			assertEquals(3, capacity(jdbcTemplate, "2026-09-12"))
		}

		applicationContext(capacity = 7, now = "2026-09-12T00:00:01Z").use { second ->
			val jdbcTemplate = second.getBean(JdbcTemplate::class.java)
			assertEquals(3, capacity(jdbcTemplate, "2026-09-12"))

			val productId = insertReadyProduct(jdbcTemplate, sellerId = 2L)
			second.getBean(SaleService::class.java).create(
				CreateSaleDto(2L, productId, LocalDate.parse("2026-09-13"), 35_000L, 100),
			)
			assertEquals(7, capacity(jdbcTemplate, "2026-09-13"))
		}
	}

	private fun applicationContext(capacity: Int, now: String): ConfigurableApplicationContext =
		SpringApplicationBuilder(BackendApplication::class.java, ContextClockConfig::class.java)
			.run(
				"--spring.datasource.url=${postgres.jdbcUrl}",
				"--spring.datasource.username=${postgres.username}",
				"--spring.datasource.password=${postgres.password}",
				"--spring.datasource.driver-class-name=org.postgresql.Driver",
				"--spring.main.web-application-type=none",
				"--product.image.s3.region=ap-northeast-2",
				"--product.image.s3.bucket=test-product-images",
				"--sale.daily-capacity=$capacity",
				"--test.clock.instant=$now",
			)

	private fun clear(jdbcTemplate: JdbcTemplate) {
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
	}

	private fun insertReadyProduct(jdbcTemplate: JdbcTemplate, sellerId: Long): Long = jdbcTemplate.queryForObject(
		"""INSERT INTO products (seller_id, name, description, status, created_at)
			VALUES (?, '컨텍스트 상품', NULL, 'READY', ?) RETURNING id""".trimIndent(),
		Long::class.java,
		sellerId,
		java.sql.Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")),
	)!!

	private fun capacity(jdbcTemplate: JdbcTemplate, saleDate: String): Int = jdbcTemplate.queryForObject(
		"SELECT capacity FROM sale_days WHERE sale_date = ?::date",
		Int::class.java,
		saleDate,
	)!!

	@TestConfiguration(proxyBeanMethods = false)
	class ContextClockConfig {
		@Bean
		@Primary
		fun fixedClock(@Value("\${test.clock.instant}") instant: String): Clock =
			Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
