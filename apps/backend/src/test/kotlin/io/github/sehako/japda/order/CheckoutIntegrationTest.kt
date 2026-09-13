package io.github.sehako.japda.order

import java.sql.Timestamp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(properties = [
	"product.image.s3.region=ap-northeast-2",
	"product.image.s3.bucket=test-product-images",
	"sale.daily-capacity=20",
])
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("체크아웃 PostgreSQL 조회 통합")
class CheckoutIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var entityManagerFactoryBean: LocalContainerEntityManagerFactoryBean

	private var saleId: Long = 0
	private var productId: Long = 0

	@BeforeEach
	fun 테스트_데이터를_준비한다() {
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM buyer_shipping_addresses")
		jdbcTemplate.update("DELETE FROM buyer_shipping_address_books")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '통합 상품', 'READY', ?) RETURNING id",
			Long::class.java, Timestamp.from(NOW),
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (DATE '2026-09-10', 20, 1)")
		saleId = jdbcTemplate.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, 1, DATE '2026-09-10', 35000, 1, ?) RETURNING id",
			Long::class.java, productId, Timestamp.from(NOW),
		)!!
		insertImage("representative", true, 0)
		insertImage("other", false, 1)
	}

	@Test
	@DisplayName("배송지가 없어도 대표 이미지와 예상 총액을 한 SQL로 조회하고 데이터를 변경하지 않는다")
	fun 배송지_없어도_한_SQL로_조회하고_데이터를_변경하지_않는다() {
		val statistics = entityManagerFactoryBean.nativeEntityManagerFactory.unwrap(SessionFactory::class.java).statistics
		statistics.isStatisticsEnabled = true
		statistics.clear()
		val before = statistics.prepareStatementCount

		mockMvc.perform(request(123, 2))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.productName").value("통합 상품"))
			.andExpect(jsonPath("$.representativeImagePath").value("/products/$productId/representative"))
			.andExpect(jsonPath("$.quantity").value(2))
			.andExpect(jsonPath("$.totalPrice").value(70000))
			.andExpect(jsonPath("$.shippingAddresses").isEmpty)

		assertEquals(1, statistics.prepareStatementCount - before)
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Int::class.java))
		assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM buyer_shipping_address_books", Int::class.java))
		assertEquals(35_000L, jdbcTemplate.queryForObject("SELECT price FROM sales WHERE id = ?", Long::class.java, saleId))
		assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM product_images", Int::class.java))
	}

	@Test
	@DisplayName("요청 구매자의 배송지 한 건만 반환한다")
	fun 요청_구매자의_배송지_한_건만_반환한다() {
		insertAddress(insertBook(123), "집")
		insertAddress(insertBook(456), "타인")

		mockMvc.perform(request(123, 1))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.shippingAddresses.length()").value(1))
			.andExpect(jsonPath("$.shippingAddresses[0].addressName").value("집"))
			.andExpect(jsonPath("$.shippingAddresses[0].recipientName").value("홍길동"))
			.andExpect(jsonPath("$.shippingAddresses[0].deliveryMessage").isEmpty)
	}

	@Test
	@DisplayName("한 book의 배송지 열 건을 식별자 오름차순으로 반환한다")
	fun 한_book의_배송지_열_건을_식별자_오름차순으로_반환한다() {
		val bookId = insertBook(123)
		val ids = (1..10).map { insertAddress(bookId, "배송지$it") }

		mockMvc.perform(request(123, 1))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.shippingAddresses.length()").value(10))
			.andExpect(jsonPath("$.shippingAddresses[0].shippingAddressId").value(ids.first()))
			.andExpect(jsonPath("$.shippingAddresses[9].shippingAddressId").value(ids.last()))
	}

	@Test
	@DisplayName("판매 일정이 없으면 404를 반환한다")
	fun 판매_일정_없으면_404를_반환한다() {
		mockMvc.perform(get("/api/checkout").header("X-Buyer-Id", "123").param("saleId", "999999").param("quantity", "1"))
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("ORDER_SALE_NOT_FOUND"))
	}

	@Test
	@DisplayName("양수가 아닌 판매 일정 식별자와 수량은 각 필드 오류를 반환한다")
	fun 양수가_아닌_판매_일정_식별자와_수량은_필드_오류를_반환한다() {
		mockMvc.perform(get("/api/checkout").header("X-Buyer-Id", "123").param("saleId", "0").param("quantity", "1"))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("ORDER_SALE_ID_INVALID"))
			.andExpect(jsonPath("$.errors.saleId").exists())
		mockMvc.perform(request(123, 0))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("ORDER_QUANTITY_INVALID"))
			.andExpect(jsonPath("$.errors.quantity").exists())
	}

	@Test
	@DisplayName("예상 총액이 Long 범위를 넘으면 409를 반환한다")
	fun 예상_총액_Long_범위_초과_409를_반환한다() {
		jdbcTemplate.update("UPDATE sales SET price = ? WHERE id = ?", Long.MAX_VALUE, saleId)

		mockMvc.perform(request(123, 2))
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("ORDER_TOTAL_PRICE_INVALID"))
	}

	@Test
	@DisplayName("대표 이미지가 없으면 판매 일정 미존재로 오인하지 않고 내부 오류를 반환한다")
	fun 대표_이미지_없으면_내부_오류를_반환한다() {
		jdbcTemplate.update("DELETE FROM product_images WHERE is_representative = true")

		mockMvc.perform(request(123, 1))
			.andExpect(status().isInternalServerError)
			.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_SERVER_ERROR"))
	}

	private fun request(buyerId: Long, quantity: Int) = get("/api/checkout")
		.header("X-Buyer-Id", buyerId.toString())
		.param("saleId", saleId.toString())
		.param("quantity", quantity.toString())

	private fun insertImage(suffix: String, representative: Boolean, order: Int) {
		jdbcTemplate.update(
			"INSERT INTO product_images (product_id, object_key, content_type, size_bytes, display_order, is_representative, created_at) VALUES (?, ?, 'image/jpeg', 100, ?, ?, ?)",
			productId, "products/$productId/$suffix", order, representative, Timestamp.from(NOW),
		)
	}

	private fun insertBook(buyerId: Long): Long = jdbcTemplate.queryForObject(
		"INSERT INTO buyer_shipping_address_books (buyer_id, created_at) VALUES (?, ?) RETURNING id",
		Long::class.java, buyerId, Timestamp.from(NOW),
	)!!

	private fun insertAddress(bookId: Long, name: String): Long = jdbcTemplate.queryForObject(
		"INSERT INTO buyer_shipping_addresses (buyer_shipping_address_book_id, address_name, recipient_name, phone_number, postal_code, address, detail_address, created_at) VALUES (?, ?, '홍길동', '010-1234-5678', '06236', '서울특별시 강남구', '101호', ?) RETURNING id",
		Long::class.java, bookId, name, Timestamp.from(NOW),
	)!!

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-10T03:00:00Z")
		@Container @ServiceConnection @JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
