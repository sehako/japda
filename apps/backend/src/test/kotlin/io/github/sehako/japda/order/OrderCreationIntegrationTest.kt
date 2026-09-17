package io.github.sehako.japda.order

import com.jayway.jsonpath.JsonPath
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
		"sale.daily-capacity=20",
	],
)
@AutoConfigureMockMvc
@Import(OrderCreationIntegrationTest.FixedClockConfig::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("주문 생성 통합")
class OrderCreationIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	private var saleId: Long = 0
	private lateinit var jwtCookie: Cookie
	private lateinit var csrfCookie: Cookie
	private lateinit var csrfToken: String
	private lateinit var csrfHeaderName: String

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM inventory_reservations")
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM sale_inventory_counters")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		jdbcTemplate.update("DELETE FROM buyer_principal_identities WHERE buyer_id = 123")
		val userId = jdbcTemplate.queryForObject(
			"INSERT INTO users (provider, provider_subject, email, created_at) VALUES ('GOOGLE', ?, ?, now()) RETURNING id",
			Long::class.java, UUID.randomUUID().toString(), "buyer-${UUID.randomUUID()}@example.com",
		)!!
		jdbcTemplate.update("INSERT INTO buyer_principal_identities (user_id, buyer_id) VALUES (?, 123)", userId)
		jwtCookie = Cookie("JAPDA_ACCESS_TOKEN", ServiceJwtIssuer(
			SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"), "http://localhost:8080", "japda-spa", Clock.systemUTC(),
		).issue(userId))
		val csrfResponse = mockMvc.perform(get("/api/auth/csrf").cookie(jwtCookie))
			.andExpect(status().isOk).andReturn().response
		csrfToken = JsonPath.read(csrfResponse.contentAsString, "$.token")
		csrfHeaderName = JsonPath.read(csrfResponse.contentAsString, "$.headerName")
		csrfCookie = assertNotNull(csrfResponse.cookies.singleOrNull { it.name == "XSRF-TOKEN" })

		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '통합 상품', 'READY', ?) RETURNING id",
			Long::class.java,
			java.sql.Timestamp.from(NOW),
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		saleId = jdbcTemplate.queryForObject(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, 1, ?, 35000, 10, ?) RETURNING id""",
			Long::class.java,
			productId,
			SALE_DATE,
			java.sql.Timestamp.from(NOW),
		)!!
		jdbcTemplate.update(
			"INSERT INTO sale_inventory_counters (sale_id, committed_quantity, created_at, updated_at) VALUES (?, 0, ?, ?)",
			saleId,
			java.sql.Timestamp.from(NOW),
			java.sql.Timestamp.from(NOW),
		)
	}

	@Test
	@DisplayName("HTTP 주문 생성은 서버 가격과 배송 스냅샷을 PostgreSQL에 저장한다")
	fun HTTP_주문_생성_서버_가격과_배송_스냅샷을_PostgreSQL에_저장한다() {
		val response = mockMvc.perform(request(UUID.randomUUID(), quantity = 2))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.paymentOrderId").isString)
			.andExpect(jsonPath("$.productName").value("통합 상품"))
			.andExpect(jsonPath("$.unitPrice").value(35000))
			.andExpect(jsonPath("$.totalPrice").value(70000))
			.andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(180).toString()))
			.andExpect(jsonPath("$.shippingAddress").doesNotExist())
			.andReturn()

		val row = jdbcTemplate.queryForMap(
			"SELECT payment_order_id, product_name, recipient_name, delivery_message, status FROM orders",
		)
		val paymentOrderId = row["payment_order_id"] as String
		assertTrue(response.response.contentAsString.contains(paymentOrderId))
		assertEquals(4, UUID.fromString(paymentOrderId).version())
		assertEquals("통합 상품", row["product_name"])
		assertEquals("홍길동", row["recipient_name"])
		assertEquals("문 앞", row["delivery_message"])
		assertEquals("PENDING_PAYMENT", row["status"])
	}

	@Test
	@DisplayName("위조한 구매자 헤더는 주문 소유자를 바꾸지 못한다")
	fun 위조한_구매자_헤더_주문_소유자를_바꾸지_못한다() {
		mockMvc.perform(request(UUID.randomUUID(), quantity = 1).header("X-Buyer-Id", "456"))
			.andExpect(status().isCreated)
		assertEquals(123L, jdbcTemplate.queryForObject("SELECT buyer_id FROM orders", Long::class.java))
	}

	@Test
	@DisplayName("같은 판매 일정의 동시 주문은 최초 판매 수량을 초과하지 않는다")
	fun 같은_판매_일정_동시_주문_최초_판매_수량을_초과하지_않는다() {
		val statuses = Executors.newFixedThreadPool(2).use { executor ->
			listOf(UUID.randomUUID(), UUID.randomUUID()).map { key ->
				executor.submit<Int> { mockMvc.perform(request(key, quantity = 6)).andReturn().response.status }
			}.map { it.get(10, TimeUnit.SECONDS) }
		}

		assertEquals(1, statuses.count { it == 201 })
		assertEquals(1, statuses.count { it == 409 })
		assertEquals(6, jdbcTemplate.queryForObject("SELECT sum(quantity) FROM orders", Int::class.java))
		assertEquals(6, committedQuantity())
		assertEquals(1, reservationCount())
	}

	@Test
	@DisplayName("같은 멱등성 키의 동시 요청은 같은 응답을 반환하고 주문 하나만 생성한다")
	fun 같은_멱등성_키_동시_요청_같은_응답과_주문_하나만_생성한다() {
		val key = UUID.randomUUID()
		val responses = Executors.newFixedThreadPool(2).use { executor ->
			(1..2).map {
				executor.submit<String> {
					mockMvc.perform(request(key, quantity = 2)).andReturn().response.contentAsString
				}
			}.map { it.get(10, TimeUnit.SECONDS) }
		}

		assertEquals(responses[0], responses[1])
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Int::class.java))
		assertEquals(2, committedQuantity())
		assertEquals(1, reservationCount())
	}

	@Test
	@DisplayName("같은 멱등성 키의 다른 요청은 충돌을 반환한다")
	fun 같은_멱등성_키_다른_요청_충돌을_반환한다() {
		val key = UUID.randomUUID()
		mockMvc.perform(request(key, quantity = 1)).andExpect(status().isCreated)

		mockMvc.perform(request(key, quantity = 2))
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("ORDER_IDEMPOTENCY_CONFLICT"))

		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Int::class.java))
		assertEquals(1, committedQuantity())
		assertEquals(1, reservationCount())
	}

	private fun committedQuantity(): Int = jdbcTemplate.queryForObject(
		"SELECT committed_quantity FROM sale_inventory_counters WHERE sale_id = ?",
		Int::class.java,
		saleId,
	)!!

	private fun reservationCount(): Int =
		jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_reservations", Int::class.java)!!

	private fun request(key: UUID, quantity: Int) = post("/api/orders")
		.cookie(jwtCookie, csrfCookie)
		.header(csrfHeaderName, csrfToken)
		.header("Idempotency-Key", key.toString())
		.contentType(MediaType.APPLICATION_JSON)
		.content(
			"""{"saleId":$saleId,"quantity":$quantity,"shippingAddress":{"recipientName":" 홍길동 ","phoneNumber":"010-1234-5678","postalCode":"06236","address":"서울시 강남구","detailAddress":"101호","deliveryMessage":" 문 앞 "}}""",
		)

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfig {
		@Bean
		@Primary
		fun fixedClock(): Clock = MutableClock(NOW)
	}

	class MutableClock(initialInstant: Instant) : Clock() {
		private val current = AtomicReference(initialInstant)
		override fun getZone(): ZoneId = ZoneOffset.UTC
		override fun withZone(zone: ZoneId): Clock = this
		override fun instant(): Instant = current.get()
	}

	private companion object {
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-11")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")

		@DynamicPropertySource
		@JvmStatic
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("auth.jwt-signing-key") { Base64.getEncoder().encodeToString(ByteArray(32) { 7 }) }
		}

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
