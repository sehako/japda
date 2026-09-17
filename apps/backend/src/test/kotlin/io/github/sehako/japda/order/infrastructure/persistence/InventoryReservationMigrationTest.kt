package io.github.sehako.japda.order.infrastructure.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.DisplayName
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("재고 예약 migration")
class InventoryReservationMigrationTest {
	@Test
	@DisplayName("기존 주문과 결제 상태를 예약과 판매 일정별 카운터로 이관한다")
	fun 기존_주문과_결제_상태_예약과_카운터로_이관한다() {
		migrateTo(SUCCESS_SCHEMA, "14")
		connection(SUCCESS_SCHEMA).use { connection ->
			seedSales(connection)
			val paidOrderId = insertOrder(connection, saleId = 1, quantity = 2, status = "PAID", expiresAt = PAST)
			insertPayment(connection, paidOrderId, "FAILED")
			val approvedOrderId = insertOrder(connection, saleId = 1, quantity = 3, expiresAt = PAST)
			insertPayment(connection, approvedOrderId, "APPROVED")
			val confirmingOrderId = insertOrder(connection, saleId = 1, quantity = 4, expiresAt = PAST)
			insertPayment(connection, confirmingOrderId, "CONFIRMING")
			val reviewingOrderId = insertOrder(connection, saleId = 1, quantity = 5, expiresAt = PAST)
			insertPayment(connection, reviewingOrderId, "REVIEW_REQUIRED")
			val failedOrderId = insertOrder(connection, saleId = 1, quantity = 6, expiresAt = FUTURE)
			insertPayment(connection, failedOrderId, "FAILED")
			insertOrder(connection, saleId = 2, quantity = 7, expiresAt = FUTURE)
			insertOrder(connection, saleId = 2, quantity = 8, expiresAt = PAST)
		}

		migrateTo(SUCCESS_SCHEMA, "15")

		connection(SUCCESS_SCHEMA).use { connection ->
			assertEquals(
				listOf("CONFIRMED", "CONFIRMED", "PAYMENT_PENDING", "PAYMENT_PENDING", "RELEASED", "RESERVED", "RELEASED"),
				connection.createStatement().use { statement ->
					statement.executeQuery("SELECT status FROM inventory_reservations ORDER BY order_id").use { result ->
						buildList { while (result.next()) add(result.getString("status")) }
					}
				},
			)
			assertEquals(
				listOf(14, 7, 0),
				connection.createStatement().use { statement ->
					statement.executeQuery("SELECT committed_quantity FROM sale_inventory_counters ORDER BY sale_id").use { result ->
						buildList { while (result.next()) add(result.getInt("committed_quantity")) }
					}
				},
			)
		}
	}

	@Test
	@DisplayName("기존 점유 수량이 최초 판매 수량을 초과하면 이관을 실패한다")
	fun 기존_점유_수량_최초_판매_수량_초과_이관_실패() {
		migrateTo(OVERSELL_SCHEMA, "14")
		connection(OVERSELL_SCHEMA).use { connection ->
			seedSale(connection, sellerId = 1, quantity = 1)
			insertOrder(connection, saleId = 1, quantity = 2, expiresAt = FUTURE)
		}

		assertFailsWith<FlywayException> { migrateTo(OVERSELL_SCHEMA, "15") }
	}

	private fun seedSales(connection: Connection) {
		seedSale(connection, sellerId = 1, quantity = 30)
		seedSale(connection, sellerId = 2, quantity = 20)
		seedSale(connection, sellerId = 3, quantity = 10)
	}

	private fun seedSale(connection: Connection, sellerId: Long, quantity: Int) {
		connection.createStatement().use { statement ->
			statement.execute(
				"INSERT INTO products (seller_id, name, status, created_at) VALUES ($sellerId, '상품', 'READY', now())",
			)
			statement.execute(
				"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES ('2026-09-${10 + sellerId}', 20, 1)",
			)
			statement.execute(
				"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
					VALUES ($sellerId, $sellerId, '2026-09-${10 + sellerId}', 35000, $quantity, now())""",
			)
		}
	}

	private fun insertOrder(
		connection: Connection,
		saleId: Long,
		quantity: Int,
		status: String = "PENDING_PAYMENT",
		expiresAt: Instant,
	): Long = connection.prepareStatement(
		"""INSERT INTO orders (
			sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
			recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
		) VALUES (?, ?, ?, ?, ?, '상품', 35000, ?, ?, '홍길동', '010', '06236', '서울', '101호', ?, ?) RETURNING id""",
	).use { statement ->
		val key = UUID.randomUUID()
		statement.setLong(1, saleId)
		statement.setLong(2, key.mostSignificantBits.and(Long.MAX_VALUE) + 1)
		statement.setObject(3, key)
		statement.setString(4, UUID.randomUUID().toString())
		statement.setInt(5, quantity)
		statement.setLong(6, 35_000L * quantity)
		statement.setString(7, status)
		statement.setObject(8, java.sql.Timestamp.from(NOW.minusSeconds(300)))
		statement.setObject(9, java.sql.Timestamp.from(expiresAt))
		statement.executeQuery().use { result -> result.next(); result.getLong("id") }
	}

	private fun insertPayment(connection: Connection, orderId: Long, status: String) {
		connection.prepareStatement(
			"""INSERT INTO payments (
				order_id, payment_key, toss_idempotency_key, status, requested_amount, created_at
			) VALUES (?, ?, ?, ?, 35000, ?)""",
		).use { statement ->
			statement.setLong(1, orderId)
			statement.setString(2, "payment-$orderId")
			statement.setString(3, UUID.randomUUID().toString())
			statement.setString(4, status)
			statement.setObject(5, java.sql.Timestamp.from(NOW))
			statement.executeUpdate()
		}
	}

	private fun migrateTo(schema: String, version: String) {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(schema)
			.target(MigrationVersion.fromVersion(version))
			.load()
			.migrate()
	}

	private fun connection(schema: String): Connection =
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).also { connection ->
			connection.createStatement().use { it.execute("SET search_path TO $schema") }
		}

	private companion object {
		const val SUCCESS_SCHEMA = "inventory_reservation_migration"
		const val OVERSELL_SCHEMA = "inventory_reservation_oversell_migration"
		val NOW: Instant = Instant.parse("1999-01-01T00:00:00Z")
		val PAST: Instant = Instant.parse("2000-01-01T00:00:00Z")
		val FUTURE: Instant = Instant.parse("2100-01-01T00:00:00Z")

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
