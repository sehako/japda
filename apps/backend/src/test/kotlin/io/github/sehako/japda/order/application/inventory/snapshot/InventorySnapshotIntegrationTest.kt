package io.github.sehako.japda.order.application.inventory.snapshot

import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.infrastructure.persistence.InventoryReservationRepositoryImpl
import io.github.sehako.japda.order.infrastructure.persistence.SaleInventoryCounterRepositoryImpl
import io.github.sehako.japda.sale.infrastructure.persistence.SaleRepositoryImpl
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
	InventorySnapshotService::class,
	ExpiredInventoryReservationReleaseService::class,
	InventoryReservationRepositoryImpl::class,
	SaleInventoryCounterRepositoryImpl::class,
	SaleRepositoryImpl::class,
	InventorySnapshotIntegrationTest.ClockConfiguration::class,
)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("재고 snapshot 통합")
class InventorySnapshotIntegrationTest {
	@Autowired
	private lateinit var inventorySnapshotService: InventorySnapshotService

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	private var saleId: Long = 0

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
		jdbcTemplate.update(
			"INSERT INTO sale_inventory_counters (sale_id, committed_quantity, created_at, updated_at) VALUES (?, 7, ?, ?)",
			saleId,
			java.sql.Timestamp.from(NOW),
			java.sql.Timestamp.from(NOW),
		)
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("동일한 DB snapshot에서 판매 수량과 유효한 예약을 읽어 가용 수량을 계산한다")
	fun 판매_수량과_유효한_예약_동일_DB_snapshot으로_계산한다() {
		insertReservation(quantity = 3, expiresAt = NOW.plusSeconds(1))
		insertReservation(quantity = 4, expiresAt = NOW)

		assertEquals(InventorySnapshotResult.Available(7), inventorySnapshotService.read(saleId))
		assertEquals(3, jdbcTemplate.queryForObject("SELECT committed_quantity FROM sale_inventory_counters WHERE sale_id = ?", Int::class.java, saleId))
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_reservations WHERE status = 'RELEASED'", Int::class.java))
	}

	private fun insertReservation(quantity: Int, expiresAt: Instant) {
		val key = UUID.randomUUID()
		val orderId = jdbcTemplate.queryForObject(
			"""INSERT INTO orders (
				sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
				recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
			) VALUES (?, ?, ?, ?, ?, '상품', 35000, ?, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', ?, ?)
			RETURNING id""",
			Long::class.java,
			saleId,
			key.mostSignificantBits.and(Long.MAX_VALUE) + 1,
			key,
			UUID.randomUUID().toString(),
			quantity,
			35_000L * quantity,
			java.sql.Timestamp.from(NOW.minusSeconds(60)),
			java.sql.Timestamp.from(expiresAt),
		)!!
		jdbcTemplate.update(
			"""INSERT INTO inventory_reservations
				(id, sale_id, order_id, quantity, status, expires_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, ?)""",
			UUID.randomUUID(),
			saleId,
			orderId,
			quantity,
			java.sql.Timestamp.from(expiresAt),
			java.sql.Timestamp.from(NOW.minusSeconds(60)),
			java.sql.Timestamp.from(NOW.minusSeconds(60)),
		)
	}

	@TestConfiguration
	class ClockConfiguration {
		@Bean
		fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
	}

	private companion object {
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-11")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
