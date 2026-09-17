package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.repository.BuyerOrderCursorBoundary
import io.github.sehako.japda.order.domain.repository.BuyerOrderQuery
import io.github.sehako.japda.order.domain.repository.BuyerOrderQueryRepository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BuyerOrderQueryRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구매자 주문 내역 영속성")
class BuyerOrderQueryRepositoryTest {
	@Autowired private lateinit var repository: BuyerOrderQueryRepository
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	private var saleId: Long = 0

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM payments")
		jdbcTemplate.update("DELETE FROM inventory_reservations")
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM sale_inventory_counters")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now()) RETURNING id",
			Long::class.java,
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", LocalDate.parse("2026-09-11"))
		saleId = jdbcTemplate.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, 1, ?, 35000, 10, now()) RETURNING id",
			Long::class.java,
			productId,
			LocalDate.parse("2026-09-11"),
		)!!
	}

	@Test
	@DisplayName("구매자 주문만 생성 시각과 주문 식별자 내림차순으로 조회한다")
	fun 구매자_주문만_최신순과_식별자_내림차순으로_조회한다() {
		val older = insertOrder(123L, "2026-09-11T05:00:00Z", "과거 상품")
		val sameTimeLower = insertOrder(123L, "2026-09-11T06:00:00Z", "동시 상품 1")
		val sameTimeHigher = insertOrder(123L, "2026-09-11T06:00:00Z", "동시 상품 2", "PAID")
		insertOrder(999L, "2026-09-11T07:00:00Z", "다른 구매자 상품")

		val result = repository.findAll(BuyerOrderQuery(123L, null, 10))

		assertEquals(listOf(sameTimeHigher, sameTimeLower, older), result.map { it.orderId })
		assertEquals(listOf("동시 상품 2", "동시 상품 1", "과거 상품"), result.map { it.productName })
		assertEquals(listOf("PAID", "PENDING_PAYMENT", "PENDING_PAYMENT"), result.map { it.status.name })
	}

	@Test
	@DisplayName("복합 커서로 페이지 사이의 중복과 누락을 방지한다")
	fun 복합_커서_페이지_중복과_누락을_방지한다() {
		val oldest = insertOrder(123L, "2026-09-11T05:00:00Z", "과거")
		val middle = insertOrder(123L, "2026-09-11T06:00:00Z", "동시 1")
		val newest = insertOrder(123L, "2026-09-11T06:00:00Z", "동시 2")
		val firstPage = repository.findAll(BuyerOrderQuery(123L, null, 2))

		val secondPage = repository.findAll(
			BuyerOrderQuery(123L, BuyerOrderCursorBoundary(firstPage.last().createdAt, firstPage.last().orderId), 2),
		)

		assertEquals(listOf(newest, middle), firstPage.map { it.orderId })
		assertEquals(listOf(oldest), secondPage.map { it.orderId })
	}

	@Test
	@DisplayName("구매자와 생성 시각과 주문 식별자 복합 인덱스가 생성된다")
	fun 구매자_주문_내역_복합_인덱스가_생성된다() {
		val definition = jdbcTemplate.queryForObject(
			"SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = 'orders_buyer_created_id_idx'",
			String::class.java,
		)!!

		assertTrue(definition.contains("(buyer_id, created_at DESC, id DESC)"))
	}

	private fun insertOrder(buyerId: Long, createdAt: String, productName: String, status: String = "PENDING_PAYMENT"): Long {
		val created = Instant.parse(createdAt)
		return jdbcTemplate.queryForObject(
			"""INSERT INTO orders (
				sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
				recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
			) VALUES (?, ?, ?, ?, 2, ?, 35000, 70000, ?, '홍길동', '010', '06236', '서울', '101호', ?, ?) RETURNING id""",
			Long::class.java,
			saleId,
			buyerId,
			UUID.randomUUID(),
			UUID.randomUUID().toString(),
			productName,
			status,
			Timestamp.from(created),
			Timestamp.from(created.plusSeconds(180)),
		)!!
	}

	companion object {
		@Container @ServiceConnection @JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
