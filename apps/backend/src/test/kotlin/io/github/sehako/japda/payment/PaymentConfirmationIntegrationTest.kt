package io.github.sehako.japda.payment

import io.github.sehako.japda.payment.application.client.TossPaymentClient
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import io.github.sehako.japda.payment.application.service.PaymentService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(properties = ["product.image.s3.region=ap-northeast-2", "product.image.s3.bucket=test-product-images"])
@AutoConfigureMockMvc
@Import(PaymentConfirmationIntegrationTest.TestBeans::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("결제 승인 통합")
class PaymentConfirmationIntegrationTest {
	@Autowired private lateinit var mvc: MockMvc
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var clock: MutableClock
	@Autowired private lateinit var toss: FakeTossClient
	@Autowired private lateinit var paymentService: PaymentService
	private var saleId: Long = 0

	@BeforeEach
	fun 초기화() {
		clock.set(NOW)
		toss.result = TossPaymentResult.Unavailable
		toss.lookupResult = null
		toss.throwOnLookup = false
		toss.confirmCalls = 0
		toss.lastIdempotencyKey = null
		toss.confirmEntered = null
		toss.confirmRelease = null
		jdbc.update("DELETE FROM payments")
		jdbc.update("DELETE FROM orders")
		jdbc.update("DELETE FROM sales")
		jdbc.update("DELETE FROM sale_days")
		jdbc.update("DELETE FROM product_images")
		jdbc.update("DELETE FROM products")
		val productId = jdbc.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', ?) RETURNING id",
			Long::class.java, java.sql.Timestamp.from(NOW),
		)!!
		jdbc.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		saleId = jdbc.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, 1, ?, 35000, 2, ?) RETURNING id",
			Long::class.java, productId, SALE_DATE, java.sql.Timestamp.from(NOW),
		)!!
	}

	@Test
	@DisplayName("승인 완료 후 같은 결제는 저장된 성공을 반환하고 주문은 결제 완료로 남는다")
	fun 승인_완료_후_같은_결제_저장된_성공을_반환한다() {
		val orderKey = UUID.randomUUID()
		val orderId = createOrder(orderKey)
		toss.result = TossPaymentResult.Record("payment-key", orderId, 70_000L, "DONE", NOW.plusSeconds(1))

		mvc.perform(confirm(orderId)).andExpect(status().isOk).andExpect(jsonPath("$.status").value("PAID"))
		val firstKey = toss.lastIdempotencyKey
		mvc.perform(confirm(orderId)).andExpect(status().isOk)

		assertNotNull(firstKey)
		assertEquals(1, toss.confirmCalls)
		assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders", String::class.java))
		assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM payments", String::class.java))
		mvc.perform(orderRequest(orderKey)).andExpect(status().isCreated).andExpect(jsonPath("$.status").value("PAID"))
	}

	@Test
	@DisplayName("응답 단절 후 만료된 주문은 예약을 유지하고 재확인 실패 후 해제한다")
	fun 응답_단절_후_만료된_주문_예약을_유지하고_실패_후_해제한다() {
		val orderId = createOrder()
		mvc.perform(confirm(orderId)).andExpect(status().isServiceUnavailable)
		val firstKey = toss.lastIdempotencyKey
		clock.set(NOW.plusSeconds(181))
		mvc.perform(orderRequest()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ORDER_QUANTITY_UNAVAILABLE"))
		toss.result = TossPaymentResult.Record("payment-key", orderId, 70_000L, "ABORTED", null)
		paymentService.reconcileDue()

		assertEquals(firstKey, toss.lastIdempotencyKey)
		assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM payments", String::class.java))
		mvc.perform(orderRequest()).andExpect(status().isCreated)
	}

	@Test
	@DisplayName("조회한 결제 정보가 주문과 다르면 재승인 없이 수동 확인으로 전이한다")
	fun 조회_결제_정보_불일치_재승인_없이_수동_확인으로_전이한다() {
		val orderId = createOrder()
		mvc.perform(confirm(orderId)).andExpect(status().isServiceUnavailable)
		clock.set(NOW.plusSeconds(31))
		toss.lookupResult = TossPaymentResult.Record("payment-key", "other-order", 70_000L, "IN_PROGRESS", null)
		toss.result = TossPaymentResult.Record("payment-key", orderId, 70_000L, "DONE", NOW.plusSeconds(32))

		paymentService.reconcileDue()

		assertEquals(1, toss.confirmCalls)
		assertEquals("REVIEW_REQUIRED", jdbc.queryForObject("SELECT status FROM payments", String::class.java))
		assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders", String::class.java))
	}

	@Test
	@DisplayName("재확인 호출이 계속 실패해도 15분 뒤 수동 확인 대상으로 전이한다")
	fun 재확인_호출_실패_15분_뒤_수동_확인으로_전이한다() {
		val orderId = createOrder()
		mvc.perform(confirm(orderId)).andExpect(status().isServiceUnavailable)
		clock.set(NOW.plusSeconds(901))
		toss.throwOnLookup = true

		paymentService.reconcileDue()

		assertEquals("REVIEW_REQUIRED", jdbc.queryForObject("SELECT status FROM payments", String::class.java))
	}

	@Test
	@DisplayName("토스 승인 대기 중 예약이 만료되어도 같은 판매 일정의 새 주문은 초과 판매되지 않는다")
	fun 토스_승인_대기_중_만료_새_주문_초과_판매를_막는다() {
		val orderId = createOrder()
		toss.result = TossPaymentResult.Record("payment-key", orderId, 70_000L, "DONE", NOW.plusSeconds(1))
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		toss.confirmEntered = entered
		toss.confirmRelease = release

		Executors.newSingleThreadExecutor().use { executor ->
			val confirmation = executor.submit<Int> { mvc.perform(confirm(orderId)).andReturn().response.status }
			try {
				assertEquals(true, entered.await(5, TimeUnit.SECONDS))
				clock.set(NOW.plusSeconds(181))
				mvc.perform(orderRequest()).andExpect(status().isConflict)
					.andExpect(jsonPath("$.code").value("ORDER_QUANTITY_UNAVAILABLE"))
			} finally {
				release.countDown()
			}
			assertEquals(200, confirmation.get(5, TimeUnit.SECONDS))
		}
		assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders", String::class.java))
	}

	@Test
	@DisplayName("다른 구매자와 잘못된 금액의 요청은 토스를 호출하지 않는다")
	fun 다른_구매자와_잘못된_금액_토스_호출을_막는다() {
		val orderId = createOrder()

		mvc.perform(confirm(orderId, buyerId = 999L))
			.andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"))
		mvc.perform(confirm(orderId, amount = 1L))
			.andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"))

		assertEquals(0, toss.confirmCalls)
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments", Int::class.java))
	}

	private fun createOrder(key: UUID = UUID.randomUUID()): String = mvc.perform(orderRequest(key))
		.andExpect(status().isCreated)
		.andReturn().response.contentAsString.let { Regex("\"paymentOrderId\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

	private fun orderRequest(key: UUID = UUID.randomUUID()) = post("/api/orders")
		.header("X-Buyer-Id", "123")
		.header("Idempotency-Key", key.toString())
		.contentType(MediaType.APPLICATION_JSON)
		.content("""{"saleId":$saleId,"quantity":2,"shippingAddress":{"recipientName":"홍길동","phoneNumber":"010","postalCode":"06236","address":"서울","detailAddress":"101호"}}""")

	private fun confirm(orderId: String, amount: Long = 70_000L, buyerId: Long = 123L) = post("/api/payments/confirm")
		.header("X-Buyer-Id", buyerId.toString())
		.contentType(MediaType.APPLICATION_JSON)
		.content("""{"paymentKey":"payment-key","orderId":"$orderId","amount":$amount}""")

	@TestConfiguration(proxyBeanMethods = false)
	class TestBeans {
		@Bean @Primary fun fixedPaymentTestClock(): MutableClock = MutableClock(NOW)
		@Bean @Primary fun fakePaymentTestToss(): FakeTossClient = FakeTossClient()
	}

	class MutableClock(initial: Instant) : Clock() {
		private val value = AtomicReference(initial)
		fun set(instant: Instant) = value.set(instant)
		override fun getZone(): ZoneId = ZoneOffset.UTC
		override fun withZone(zone: ZoneId): Clock = this
		override fun instant(): Instant = value.get()
	}

	class FakeTossClient : TossPaymentClient {
		var result: TossPaymentResult = TossPaymentResult.Unavailable
		var lookupResult: TossPaymentResult? = null
		var throwOnLookup = false
		var confirmCalls = 0
		var lastIdempotencyKey: String? = null
		var confirmEntered: CountDownLatch? = null
		var confirmRelease: CountDownLatch? = null
		override fun confirm(paymentKey: String, orderId: String, amount: Long, idempotencyKey: String): TossPaymentResult {
			confirmCalls++
			lastIdempotencyKey = idempotencyKey
			confirmEntered?.countDown()
			confirmRelease?.await(5, TimeUnit.SECONDS)
			return result
		}
		override fun lookup(paymentKey: String): TossPaymentResult {
			if (throwOnLookup) throw IllegalStateException("테스트 조회 실패")
			return lookupResult ?: result
		}
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-13T06:00:00Z")
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-13")
		@Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
