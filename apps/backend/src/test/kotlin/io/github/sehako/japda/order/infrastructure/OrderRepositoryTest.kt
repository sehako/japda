package io.github.sehako.japda.order.infrastructure

import io.github.sehako.japda.order.domain.Order
import io.github.sehako.japda.order.domain.OrderRepository
import io.github.sehako.japda.order.domain.OrderRequest
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OrderRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("주문 영속성")
class OrderRepositoryTest {
	@Autowired
	private lateinit var orderRepository: OrderRepository

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	private var saleId: Long = 0

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now()) RETURNING id",
			Long::class.java,
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		saleId = jdbcTemplate.queryForObject(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, 1, ?, 35000, 10, now()) RETURNING id""",
			Long::class.java,
			productId,
			SALE_DATE,
		)!!
	}

	@Test
	@DisplayName("주문을 저장하고 구매자와 멱등성 키로 조회한다")
	fun 주문_저장_구매자와_멱등성_키로_조회한다() {
		val saved = orderRepository.save(order())

		val found = orderRepository.findByBuyerIdAndIdempotencyKey(123L, IDEMPOTENCY_KEY)

		assertEquals(saved.id, found?.id)
		assertEquals("홍길동", found?.shippingAddress?.recipientName)
	}

	@Test
	@DisplayName("현재 시각보다 늦게 만료되는 결제 대기 주문만 예약 수량에 포함한다")
	fun 현재_시각보다_늦게_만료되는_주문만_예약_수량에_포함한다() {
		insertOrder(UUID.randomUUID(), 2, NOW.plusSeconds(1))
		insertOrder(UUID.randomUUID(), 3, NOW)
		insertOrder(UUID.randomUUID(), 4, NOW.minusSeconds(1))

		assertEquals(2L, orderRepository.sumActiveReservedQuantity(saleId, NOW))
	}

	@Test
	@DisplayName("구매자와 멱등성 키 유일성 충돌만 전용 persistence 오류로 변환한다")
	fun 구매자와_멱등성_키_유일성_충돌만_전용_persistence_오류로_변환한다() {
		orderRepository.save(order())

		assertFailsWith<OrderIdempotencyPersistenceException> {
			orderRepository.save(order())
		}
	}

	@Test
	@DisplayName("존재하지 않는 판매 일정 FK 오류는 멱등성 오류로 변환하지 않는다")
	fun 존재하지_않는_판매_일정_FK_오류_멱등성_오류로_변환하지_않는다() {
		assertFailsWith<DataIntegrityViolationException> {
			orderRepository.save(order(saleId = Long.MAX_VALUE))
		}
	}

	@Test
	@DisplayName("양수가 아닌 구매자와 수량은 DB check 제약으로 거절한다")
	fun 양수가_아닌_구매자와_수량_DB_check_제약으로_거절한다() {
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"""INSERT INTO orders (
					sale_id, buyer_id, idempotency_key, quantity, product_name, unit_price, total_price, status,
					recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
				) VALUES (?, 0, ?, 0, '상품', 35000, 35000, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', ?, ?)""",
				saleId,
				UUID.randomUUID(),
				java.sql.Timestamp.from(NOW),
				java.sql.Timestamp.from(NOW.plusSeconds(180)),
			)
		}
	}

	@Test
	@DisplayName("유효 예약 집계를 위한 부분 인덱스가 생성된다")
	fun 유효_예약_집계_부분_인덱스가_생성된다() {
		val definition = jdbcTemplate.queryForObject(
			"SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = 'orders_active_reservation_idx'",
			String::class.java,
		)!!

		assertTrue(definition.contains("(sale_id, expires_at)"))
		assertTrue(definition.contains("status"))
		assertTrue(definition.contains("PENDING_PAYMENT"))
	}

	private fun order(saleId: Long = this.saleId): Order = Order.create(
		OrderRequest.create(
			123L,
			IDEMPOTENCY_KEY,
			saleId,
			2,
			"홍길동",
			"010-1234-5678",
			"06236",
			"서울시 강남구",
			"101호",
			null,
		),
		"상품",
		35_000L,
		NOW,
	)

	private fun insertOrder(key: UUID, quantity: Int, expiresAt: Instant) {
		jdbcTemplate.update(
			"""INSERT INTO orders (
				sale_id, buyer_id, idempotency_key, quantity, product_name, unit_price, total_price, status,
				recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
			) VALUES (?, ?, ?, ?, '상품', 35000, ?, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', ?, ?)""",
			saleId,
			key.mostSignificantBits.and(Long.MAX_VALUE) + 1,
			key,
			quantity,
			35_000L * quantity,
			java.sql.Timestamp.from(NOW.minusSeconds(60)),
			java.sql.Timestamp.from(expiresAt),
		)
	}

	private companion object {
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-11")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
		val IDEMPOTENCY_KEY: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
