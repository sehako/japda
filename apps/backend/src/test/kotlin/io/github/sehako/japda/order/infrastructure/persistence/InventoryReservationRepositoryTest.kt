package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
@Import(InventoryReservationRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("재고 예약 영속성")
class InventoryReservationRepositoryTest {
	@Autowired
	private lateinit var repository: InventoryReservationRepository

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	private var saleId: Long = 0
	private var firstOrderId: Long = 0
	private var secondOrderId: Long = 0

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM inventory_reservations")
		jdbcTemplate.update("DELETE FROM payments")
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM sale_inventory_counters")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', ?) RETURNING id",
			Long::class.java,
			java.sql.Timestamp.from(NOW),
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		saleId = jdbcTemplate.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, 1, ?, 1000, 10, ?) RETURNING id",
			Long::class.java,
			productId,
			SALE_DATE,
			java.sql.Timestamp.from(NOW),
		)!!
		firstOrderId = insertOrder(1L)
		secondOrderId = insertOrder(2L)
	}

	@Test
	@DisplayName("예약을 저장하면 주문 식별자로 같은 예약을 조회한다")
	fun 예약_저장_주문_식별자로_조회한다() {
		repository.save(reservation(firstOrderId, expiresAt = NOW.plusSeconds(1)))

		val found = assertNotNull(repository.findByOrderId(firstOrderId))

		assertEquals(saleId, found.saleId)
		assertEquals(2, found.quantity)
		assertEquals(InventoryReservationStatus.RESERVED, found.status)
	}

	@Test
	@DisplayName("만료 시각 전 RESERVED 예약만 PAYMENT_PENDING으로 변경한다")
	fun 만료_시각_전_RESERVED_예약만_PAYMENT_PENDING으로_변경한다() {
		repository.save(reservation(firstOrderId, expiresAt = NOW.plusSeconds(1)))
		repository.save(reservation(secondOrderId, expiresAt = NOW))

		assertTrue(repository.markPaymentPendingIfReservedAndNotExpired(firstOrderId, NOW))
		assertFalse(repository.markPaymentPendingIfReservedAndNotExpired(secondOrderId, NOW))
		assertEquals(InventoryReservationStatus.PAYMENT_PENDING, repository.findByOrderId(firstOrderId)?.status)
		assertEquals(InventoryReservationStatus.RESERVED, repository.findByOrderId(secondOrderId)?.status)
	}

	@Test
	@DisplayName("기대 상태가 일치할 때만 주문 예약 상태를 변경한다")
	fun 기대_상태_일치_주문_예약_상태를_변경한다() {
		repository.save(reservation(firstOrderId, expiresAt = NOW.plusSeconds(1)))

		assertTrue(
			repository.transitionByOrderId(
				firstOrderId,
				InventoryReservationStatus.RESERVED,
				InventoryReservationStatus.RELEASED,
				NOW.plusSeconds(1),
			),
		)
		assertFalse(
			repository.transitionByOrderId(
				firstOrderId,
				InventoryReservationStatus.RESERVED,
				InventoryReservationStatus.RELEASED,
				NOW.plusSeconds(2),
			),
		)
	}

	@Test
	@DisplayName("만료 조회는 같은 판매 일정의 만료된 RESERVED 예약만 반환한다")
	fun 만료_조회_같은_판매_일정의_만료_RESERVED_예약만_반환한다() {
		val expired = repository.save(reservation(firstOrderId, expiresAt = NOW))
		repository.save(reservation(secondOrderId, expiresAt = NOW.plusSeconds(1)))

		val found = repository.findExpiredReservedBySaleId(saleId, NOW)

		assertEquals(listOf(expired.id), found.map { it.id })
	}

	private fun reservation(orderId: Long, expiresAt: Instant) = InventoryReservation.reserve(
		id = UUID.randomUUID(),
		saleId = saleId,
		orderId = orderId,
		quantity = 2,
		expiresAt = expiresAt,
		createdAt = NOW.minusSeconds(60),
	)

	private fun insertOrder(buyerId: Long): Long = jdbcTemplate.queryForObject(
		"""INSERT INTO orders (
			sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
			recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
		) VALUES (?, ?, ?, ?, 2, '상품', 1000, 2000, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', ?, ?)
		RETURNING id""",
		Long::class.java,
		saleId,
		buyerId,
		UUID.randomUUID(),
		UUID.randomUUID().toString(),
		java.sql.Timestamp.from(NOW.minusSeconds(60)),
		java.sql.Timestamp.from(NOW.plusSeconds(180)),
	)!!

	private companion object {
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-11")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
