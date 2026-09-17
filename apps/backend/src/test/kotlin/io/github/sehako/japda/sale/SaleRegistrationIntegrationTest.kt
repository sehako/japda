package io.github.sehako.japda.sale

import com.jayway.jsonpath.JsonPath
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
		"sale.daily-capacity=20",
	],
)
@AutoConfigureMockMvc
@Import(SaleRegistrationIntegrationTest.FixedClockConfig::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매 일정 등록 통합")
class SaleRegistrationIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var clock: MutableClock

	@Autowired
	private lateinit var dataSource: DataSource

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		clock.set(FIXED_INSTANT)
		jdbcTemplate.update("DELETE FROM inventory_reservations")
		jdbcTemplate.update("DELETE FROM sale_inventory_counters")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		jdbcTemplate.update("DELETE FROM seller_principal_identities")
	}

	@Test
	@DisplayName("판매일 잠금을 기다리는 동안 마감 시각이 지나면 등록을 거절한다")
	fun 판매일_잠금_대기_중_마감_등록을_거절한다() {
		val productId = insertReadyProduct(1L)
		val seller = authenticatedSeller(1L)
		jdbcTemplate.update(
			"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (DATE '2026-09-12', 20, 0)",
		)

		dataSource.connection.use { lockConnection ->
			lockConnection.autoCommit = false
			lockConnection.prepareStatement(
				"SELECT sale_date FROM sale_days WHERE sale_date = DATE '2026-09-12' FOR UPDATE",
			).use { statement -> statement.executeQuery().use { result -> result.next() } }

			Executors.newSingleThreadExecutor().use { executor ->
				val response = executor.submit<Int> {
					mockMvc.perform(
						post("/api/sales")
							.cookie(seller.jwtCookie, *seller.csrfCookies)
							.header(seller.csrfHeaderName, seller.csrfToken)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""{"productId":$productId,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
					).andReturn().response.status
				}

				waitUntilSaleRequestWaitsForLock()
				assertFalse(response.isDone)
				clock.set(Instant.parse("2026-09-11T15:00:00Z"))
				lockConnection.commit()

				assertEquals(409, response.get(10, TimeUnit.SECONDS))
			}
		}

		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"SELECT registered_count FROM sale_days WHERE sale_date = DATE '2026-09-12'",
				Int::class.java,
			),
		)
	}

	@Test
	@DisplayName("HTTP 판매 일정 등록 요청은 PostgreSQL에 일정과 판매일 자리를 함께 저장한다")
	fun HTTP_판매_일정_등록_요청_PostgreSQL에_일정과_자리를_저장한다() {
		val productId = insertReadyProduct(1L)
		val seller = authenticatedSeller(1L)

		mockMvc.perform(
			post("/api/sales")
				.cookie(seller.jwtCookie, *seller.csrfCookies)
				.header(seller.csrfHeaderName, seller.csrfToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"productId":$productId,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
		)
			.andExpect(status().isCreated)
			.andExpect(header().doesNotExist("Location"))
			.andExpect(jsonPath("$.productId").value(productId))
			.andExpect(jsonPath("$.sellerId").value(1))
			.andExpect(jsonPath("$.startsAt").value("2026-09-11T15:00:00Z"))
			.andExpect(jsonPath("$.endsAt").value("2026-09-12T15:00:00Z"))
			.andExpect(jsonPath("$.createdAt").value(FIXED_INSTANT.toString()))

		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
		assertEquals(
			0,
			jdbcTemplate.queryForObject("SELECT committed_quantity FROM sale_inventory_counters", Int::class.java),
		)
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"SELECT registered_count FROM sale_days WHERE sale_date = DATE '2026-09-12'",
				Int::class.java,
			),
		)
	}

	@Test
	@DisplayName("재고 카운터 저장 실패 시 판매 일정과 판매일 자리를 함께 롤백한다")
	fun 재고_카운터_저장_실패_판매_일정과_판매일_자리_롤백() {
		val productId = insertReadyProduct(1L)
		val seller = authenticatedSeller(1L)
		jdbcTemplate.execute(
			"""CREATE FUNCTION reject_sale_inventory_counter() RETURNS trigger AS ${'$'}${'$'}
				BEGIN
					RAISE EXCEPTION '재고 카운터 저장 실패';
				END;
				${'$'}${'$'} LANGUAGE plpgsql""",
		)
		jdbcTemplate.execute(
			"""CREATE TRIGGER reject_sale_inventory_counter_insert
				BEFORE INSERT ON sale_inventory_counters
				FOR EACH ROW EXECUTE FUNCTION reject_sale_inventory_counter()""",
		)

		try {
			mockMvc.perform(
				post("/api/sales")
					.cookie(seller.jwtCookie, *seller.csrfCookies)
					.header(seller.csrfHeaderName, seller.csrfToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""{"productId":$productId,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
			).andExpect(status().is5xxServerError)
		} finally {
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_sale_inventory_counter_insert ON sale_inventory_counters")
			jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_sale_inventory_counter()")
		}

		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sale_inventory_counters", Int::class.java))
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sale_days", Int::class.java))
	}

	@Test
	@DisplayName("판매자 헤더만 보낸 등록 요청은 인증 실패를 반환한다")
	fun 판매자_헤더만_보낸_등록_요청_인증_실패() {
		mockMvc.perform(
			post("/api/sales")
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
		)
			.andExpect(status().isUnauthorized)
			.andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
	}

	@Test
	@DisplayName("인증된 사용자에게 판매자 연결이 없으면 판매 등록을 거부한다")
	fun 인증된_사용자_판매자_연결_없음_판매_등록_거부() {
		val userId = createUser()
		val identity = authenticatedUser(userId)

		mockMvc.perform(
			post("/api/sales")
				.cookie(identity.jwtCookie, *identity.csrfCookies)
				.header(identity.csrfHeaderName, identity.csrfToken)
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
		)
			.andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("AUTH_SELLER_LINK_REQUIRED"))
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
	}

	@Test
	@DisplayName("위조한 판매자 헤더로 다른 판매자의 상품을 등록할 수 없다")
	fun 위조한_판매자_헤더_다른_판매자의_상품_등록_불가() {
		val productId = insertReadyProduct(2L)
		val seller = authenticatedSeller(1L)

		mockMvc.perform(
			post("/api/sales")
				.cookie(seller.jwtCookie, *seller.csrfCookies)
				.header(seller.csrfHeaderName, seller.csrfToken)
				.header("X-Seller-Id", "2")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"productId":$productId,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
		)
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("SALE_PRODUCT_NOT_FOUND"))
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
	}

	@Test
	@DisplayName("인증된 판매 등록 요청에 CSRF 토큰이 없으면 등록 전에 거부한다")
	fun 인증된_판매_등록_요청_CSRF_토큰_없음_등록_전_거부() {
		val productId = insertReadyProduct(1L)
		val seller = authenticatedSeller(1L)

		mockMvc.perform(
			post("/api/sales")
				.cookie(seller.jwtCookie, *seller.csrfCookies)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"productId":$productId,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
		)
			.andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"))
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
	}

	@Test
	@DisplayName("상품 검증 실패 시 최초 생성한 판매일 행도 롤백한다")
	fun 상품_검증_실패_최초_판매일_행도_롤백한다() {
		val seller = authenticatedSeller(1L)
		mockMvc.perform(
			post("/api/sales")
				.cookie(seller.jwtCookie, *seller.csrfCookies)
				.header(seller.csrfHeaderName, seller.csrfToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"productId":999,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
		)
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("SALE_PRODUCT_NOT_FOUND"))

		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM sale_days", Int::class.java))
	}

	@Test
	@DisplayName("판매일 정원보다 많은 동시 요청은 정원만큼만 등록한다")
	fun 판매일_정원_초과_동시_요청_정원만큼만_등록한다() {
		val productIds = (1L..21L).associateWith(::insertReadyProduct)
		val sellers = productIds.keys.associateWith(::authenticatedSeller)

		val statuses = Executors.newFixedThreadPool(21).use { executor ->
			productIds.map { (sellerId, productId) ->
				val seller = sellers.getValue(sellerId)
				executor.submit<Int> {
					mockMvc.perform(
						post("/api/sales")
							.cookie(seller.jwtCookie, *seller.csrfCookies)
							.header(seller.csrfHeaderName, seller.csrfToken)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""{"productId":$productId,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
					).andReturn().response.status
				}
			}.map { it.get(20, TimeUnit.SECONDS) }
		}

		assertEquals(20, statuses.count { it == 201 })
		assertEquals(1, statuses.count { it == 409 })
		assertEquals(20, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
		assertEquals(
			20,
			jdbcTemplate.queryForObject(
				"SELECT registered_count FROM sale_days WHERE sale_date = DATE '2026-09-12'",
				Int::class.java,
			),
		)
	}

	@Test
	@DisplayName("같은 판매자의 동시 요청은 정확히 한 건과 한 자리만 등록한다")
	fun 같은_판매자_동시_요청_한_건과_한_자리만_등록한다() {
		val productIds = listOf(insertReadyProduct(1L), insertReadyProduct(1L))
		val seller = authenticatedSeller(1L)

		val statuses = Executors.newFixedThreadPool(2).use { executor ->
			productIds.map { productId ->
				executor.submit<Int> {
					mockMvc.perform(
						post("/api/sales")
							.cookie(seller.jwtCookie, *seller.csrfCookies)
							.header(seller.csrfHeaderName, seller.csrfToken)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""{"productId":$productId,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
					).andReturn().response.status
				}
			}.map { it.get(10, TimeUnit.SECONDS) }
		}

		assertEquals(1, statuses.count { it == 201 })
		assertEquals(1, statuses.count { it == 409 })
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"SELECT registered_count FROM sale_days WHERE sale_date = DATE '2026-09-12'",
				Int::class.java,
			),
		)
	}

	private fun insertReadyProduct(sellerId: Long): Long = jdbcTemplate.queryForObject(
		"""INSERT INTO products (seller_id, name, description, status, created_at)
			VALUES (?, '통합 상품', NULL, 'READY', ?) RETURNING id""".trimIndent(),
		Long::class.java,
		sellerId,
		java.sql.Timestamp.from(FIXED_INSTANT),
	)!!

	private fun authenticatedSeller(sellerId: Long): AuthenticatedSeller {
		val userId = createUser()
		jdbcTemplate.update(
			"INSERT INTO seller_principal_identities (user_id, seller_id) VALUES (?, ?)",
			userId,
			sellerId,
		)
		return authenticatedUser(userId)
	}

	private fun createUser(): Long = jdbcTemplate.queryForObject(
		"INSERT INTO users (provider, provider_subject, email, created_at) VALUES ('GOOGLE', ?, ?, now()) RETURNING id",
		Long::class.java,
		UUID.randomUUID().toString(),
		"sale-test-${UUID.randomUUID()}@example.com",
	)!!

	private fun authenticatedUser(userId: Long): AuthenticatedSeller {
		val jwt = ServiceJwtIssuer(
			SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"),
			"http://localhost:8080",
			"japda-spa",
			Clock.systemUTC(),
		).issue(userId)
		val jwtCookie = Cookie("JAPDA_ACCESS_TOKEN", jwt)
		val csrfResponse = mockMvc.perform(get("/api/auth/csrf").cookie(jwtCookie))
			.andExpect(status().isOk)
			.andReturn().response
		return AuthenticatedSeller(
			jwtCookie,
			csrfResponse.cookies,
			JsonPath.read(csrfResponse.contentAsString, "$.headerName"),
			JsonPath.read(csrfResponse.contentAsString, "$.token"),
		)
	}

	private data class AuthenticatedSeller(
		val jwtCookie: Cookie,
		val csrfCookies: Array<Cookie>,
		val csrfHeaderName: String,
		val csrfToken: String,
	)

	private fun waitUntilSaleRequestWaitsForLock() {
		repeat(100) {
			val waiting = jdbcTemplate.queryForObject(
				"""SELECT EXISTS (
					SELECT 1 FROM pg_stat_activity
					WHERE datname = current_database()
					  AND wait_event_type = 'Lock'
					  AND query LIKE '%sale_days%'
				)""".trimIndent(),
				Boolean::class.java,
			) == true
			if (waiting) return
			Thread.sleep(20)
		}
		error("판매 일정 등록 요청이 판매일 행 잠금을 기다리지 않았습니다.")
	}

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfig {
		@Bean
		@Primary
		fun fixedClock(): MutableClock = MutableClock(FIXED_INSTANT)
	}

	class MutableClock(initialInstant: Instant) : Clock() {
		private val current = AtomicReference(initialInstant)

		fun set(instant: Instant) {
			current.set(instant)
		}

		override fun getZone(): ZoneId = ZoneOffset.UTC

		override fun withZone(zone: ZoneId): Clock = this

		override fun instant(): Instant = current.get()
	}

	companion object {
		private val FIXED_INSTANT = Instant.parse("2026-09-11T00:00:01Z")

		@DynamicPropertySource
		@JvmStatic
		fun authProperties(registry: DynamicPropertyRegistry) {
			registry.add("auth.jwt-signing-key") { Base64.getEncoder().encodeToString(ByteArray(32) { 7 }) }
		}

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
