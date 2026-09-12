package io.github.sehako.japda.shippingaddress

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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

@SpringBootTest(
	properties = [
		"product.image.s3.region=ap-northeast-2",
		"product.image.s3.bucket=test-product-images",
		"sale.daily-capacity=20",
	],
)
@AutoConfigureMockMvc
@Import(BuyerShippingAddressRegistrationIntegrationTest.FixedClockConfig::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구매자 배송지 등록 통합")
class BuyerShippingAddressRegistrationIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM buyer_shipping_addresses")
		jdbcTemplate.update("DELETE FROM buyer_shipping_address_books")
	}

	@Test
	@DisplayName("HTTP 등록은 정규화한 배송지를 PostgreSQL에 저장하고 201 응답을 반환한다")
	fun HTTP_등록_정규화한_배송지를_PostgreSQL에_저장하고_201을_반환한다() {
		mockMvc.perform(request(123L, " 집 ", " 빈 값은 제거 "))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.shippingAddressId").isNumber)
			.andExpect(jsonPath("$.addressName").value("집"))
			.andExpect(jsonPath("$.recipientName").value("홍길동"))
			.andExpect(jsonPath("$.deliveryMessage").value("빈 값은 제거"))
			.andExpect(jsonPath("$.createdAt").value(NOW.toString()))
			.andExpect(jsonPath("$.buyerId").doesNotExist())
			.andExpect(jsonPath("$.buyerShippingAddressBookId").doesNotExist())

		val row = jdbcTemplate.queryForMap("SELECT address_name, recipient_name, delivery_message FROM buyer_shipping_addresses")
		assertEquals("집", row["address_name"])
		assertEquals("홍길동", row["recipient_name"])
		assertEquals("빈 값은 제거", row["delivery_message"])
	}

	@Test
	@DisplayName("같은 구매자의 배송지명 중복은 409로 거절한다")
	fun 같은_구매자_배송지명_중복_409로_거절한다() {
		mockMvc.perform(request(123L, "집")).andExpect(status().isCreated)

		mockMvc.perform(request(123L, " 집 "))
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("BUYER_SHIPPING_ADDRESS_NAME_DUPLICATED"))
			.andExpect(jsonPath("$.errors.addressName").exists())
	}

	@Test
	@DisplayName("배송지가 이미 10개이면 중복보다 개수 초과 오류를 우선한다")
	fun 배송지_10개_중복보다_개수_초과_오류를_우선한다() {
		(1..10).forEach { mockMvc.perform(request(123L, "배송지$it")).andExpect(status().isCreated) }

		mockMvc.perform(request(123L, "배송지1"))
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("BUYER_SHIPPING_ADDRESS_LIMIT_EXCEEDED"))
	}

	@Test
	@DisplayName("배송지 9개인 같은 구매자의 동시 등록은 한 건만 성공한다")
	fun 배송지_9개_같은_구매자_동시_등록_한_건만_성공한다() {
		(1..9).forEach { mockMvc.perform(request(123L, "배송지$it")).andExpect(status().isCreated) }

		val statuses = Executors.newFixedThreadPool(2).use { executor ->
			listOf("회사", "부모님 댁").map { name ->
				executor.submit<Int> { mockMvc.perform(request(123L, name)).andReturn().response.status }
			}.map { it.get(10, TimeUnit.SECONDS) }
		}

		assertEquals(1, statuses.count { it == 201 })
		assertEquals(1, statuses.count { it == 409 })
		assertEquals(10, jdbcTemplate.queryForObject("SELECT count(*) FROM buyer_shipping_addresses", Int::class.java))
	}

	@Test
	@DisplayName("서로 다른 구매자는 같은 배송지명을 독립적으로 등록한다")
	fun 서로_다른_구매자_같은_배송지명을_등록한다() {
		mockMvc.perform(request(123L, "집")).andExpect(status().isCreated)
		mockMvc.perform(request(124L, "집")).andExpect(status().isCreated)

		assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM buyer_shipping_addresses", Int::class.java))
	}

	private fun request(buyerId: Long, addressName: String, deliveryMessage: String = "문 앞") =
		post("/api/shipping-addresses")
			.header("X-Buyer-Id", buyerId.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(
				"""{"addressName":"$addressName","recipientName":" 홍길동 ","phoneNumber":" 010-1234-5678 ","postalCode":" 06236 ","address":" 서울시 강남구 ","detailAddress":" 101호 ","deliveryMessage":"$deliveryMessage"}""",
			)

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfig {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-12T03:34:56Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
