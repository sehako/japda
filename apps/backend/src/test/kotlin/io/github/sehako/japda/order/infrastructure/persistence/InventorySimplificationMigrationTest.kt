package io.github.sehako.japda.order.infrastructure.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.DisplayName
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("재고 관리 단순화 migration")
class InventorySimplificationMigrationTest {
	@Test
	@DisplayName("판매 점유 수량의 기본값과 범위 제약을 추가한다")
	fun 판매_점유_수량_기본값과_범위_제약_추가() {
		migrateTo(SALES_SCHEMA, "19")
		connection(SALES_SCHEMA).use { connection ->
			val saleId = insertSale(connection, quantity = 10)

			migrateTo(SALES_SCHEMA, "20")

			assertEquals(
				listOf("integer", "NO", "0"),
				connection.prepareStatement(
					"""SELECT data_type, is_nullable, column_default
						FROM information_schema.columns
						WHERE table_schema = current_schema() AND table_name = 'sales'
							AND column_name = 'committed_quantity'""",
				).use { statement ->
					statement.executeQuery().use { result ->
						result.next()
						listOf(result.getString(1), result.getString(2), result.getString(3))
					}
				},
			)
			assertEquals(
				0,
				connection.prepareStatement("SELECT committed_quantity FROM sales WHERE id = ?").use { statement ->
					statement.setLong(1, saleId)
					statement.executeQuery().use { result ->
						result.next()
						result.getInt(1)
					}
				},
			)

			assertFailsWith<SQLException> {
				connection.prepareStatement("UPDATE sales SET committed_quantity = -1 WHERE id = ?").use { statement ->
					statement.setLong(1, saleId)
					statement.executeUpdate()
				}
			}
			assertFailsWith<SQLException> {
				connection.prepareStatement("UPDATE sales SET committed_quantity = 11 WHERE id = ?").use { statement ->
					statement.setLong(1, saleId)
					statement.executeUpdate()
				}
			}
		}
	}

	@Test
	@DisplayName("주문 상태를 네 가지 상태로 제한한다")
	fun 주문_상태를_네_가지_상태로_제한() {
		migrateTo(ORDER_SCHEMA, "19")
		connection(ORDER_SCHEMA).use { connection ->
			val saleId = insertSale(connection, quantity = 10)
			migrateTo(ORDER_SCHEMA, "20")

			listOf("PENDING_PAYMENT", "PAID", "EXPIRED", "PAYMENT_FAILED").forEach { status ->
				insertOrder(connection, saleId, status)
			}

			assertFailsWith<SQLException> {
				insertOrder(connection, saleId, "PAYMENT_PROCESSING")
			}
		}
	}

	@Test
	@DisplayName("정산 자식 데이터부터 주문과 결제를 정리하고 기존 재고 테이블을 제거한다")
	fun 정산_자식_데이터부터_주문과_결제를_정리하고_기존_재고_테이블_제거() {
		migrateTo(CLEANUP_SCHEMA, "19")
		connection(CLEANUP_SCHEMA).use { connection ->
			val saleId = insertSale(connection, quantity = 10)
			val recipientUserId = insertUser(connection)
			val orderId = insertOrder(connection, saleId, "PAID")
			val paymentId = insertPayment(connection, orderId)
			val settlementRunId = insertSettlementRun(connection)
			insertSettlementDetail(connection, settlementRunId, paymentId, orderId, saleId, recipientUserId)
			insertSettlementEntry(connection, paymentId, orderId, saleId, recipientUserId)
			insertInventoryReservation(connection, saleId, orderId)
			insertSaleInventoryCounter(connection, saleId)

			migrateTo(CLEANUP_SCHEMA, "20")

			assertEquals(0, count(connection, "settlement_details"))
			assertEquals(0, count(connection, "settlement_entries"))
			assertEquals(0, count(connection, "payments"))
			assertEquals(0, count(connection, "orders"))
			assertFalse(tableExists(connection, "inventory_reservations"))
			assertFalse(tableExists(connection, "sale_inventory_counters"))
			assertEquals(0, scalarInt(connection, "SELECT committed_quantity FROM sales WHERE id = $saleId"))
		}
	}

	private fun insertSale(connection: Connection, quantity: Int): Long {
		val productId = connection.prepareStatement(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now()) RETURNING id",
		).use { statement ->
			statement.executeQuery().use { result ->
				result.next()
				result.getLong(1)
			}
		}
		connection.prepareStatement(
			"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (DATE '2026-09-20', 20, 1)",
		).use { it.executeUpdate() }
		return connection.prepareStatement(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, 1, DATE '2026-09-20', 35000, ?, now()) RETURNING id""",
		).use { statement ->
			statement.setLong(1, productId)
			statement.setInt(2, quantity)
			statement.executeQuery().use { result ->
				result.next()
				result.getLong(1)
			}
		}
	}

	private fun insertUser(connection: Connection): Long = connection.prepareStatement(
		"INSERT INTO users (provider, provider_subject, email, created_at) VALUES ('GOOGLE', ?, ?, now()) RETURNING id",
	).use { statement ->
		statement.setString(1, UUID.randomUUID().toString())
		statement.setString(2, "recipient-${UUID.randomUUID()}@example.com")
		statement.executeQuery().use { result ->
			result.next()
			result.getLong(1)
		}
	}

	private fun insertOrder(connection: Connection, saleId: Long, status: String): Long = connection.prepareStatement(
		"""INSERT INTO orders (
			sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
			recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
		) VALUES (?, 1, ?, ?, 1, '상품', 35000, 35000, ?, '홍길동', '010-0000-0000', '06236', '서울', '101호', now(), now() + interval '1 hour')
		RETURNING id""",
	).use { statement ->
		statement.setLong(1, saleId)
		statement.setObject(2, UUID.randomUUID())
		statement.setString(3, "payment-order-${UUID.randomUUID()}")
		statement.setString(4, status)
		statement.executeQuery().use { result ->
			result.next()
			result.getLong(1)
		}
	}

	private fun insertPayment(connection: Connection, orderId: Long): Long = connection.prepareStatement(
		"""INSERT INTO payments (
			order_id, payment_key, toss_idempotency_key, status, requested_amount, created_at, approved_at
		) VALUES (?, ?, ?, 'APPROVED', 35000, now(), now()) RETURNING id""",
	).use { statement ->
		statement.setLong(1, orderId)
		statement.setString(2, "payment-$orderId")
		statement.setString(3, UUID.randomUUID().toString())
		statement.executeQuery().use { result ->
			result.next()
			result.getLong(1)
		}
	}

	private fun insertSettlementRun(connection: Connection): Long = connection.prepareStatement(
		"""INSERT INTO settlement_runs (
			settlement_date, platform_fee_rate_bps, status, started_at, created_at
		) VALUES (DATE '2026-09-20', 1000, 'COLLECTING', now(), now()) RETURNING id""",
	).use { statement ->
		statement.executeQuery().use { result ->
			result.next()
			result.getLong(1)
		}
	}

	private fun insertSettlementDetail(
		connection: Connection,
		settlementRunId: Long,
		paymentId: Long,
		orderId: Long,
		saleId: Long,
		recipientUserId: Long,
	) {
		connection.prepareStatement(
			"""INSERT INTO settlement_details (
				settlement_run_id, payment_id, order_id, sale_id, seller_id, recipient_user_id,
				quantity, unit_price, gross_amount, payment_approved_at, created_at
			) VALUES (?, ?, ?, ?, 1, ?, 1, 35000, 35000, now(), now())""",
		).use { statement ->
			statement.setLong(1, settlementRunId)
			statement.setLong(2, paymentId)
			statement.setLong(3, orderId)
			statement.setLong(4, saleId)
			statement.setLong(5, recipientUserId)
			statement.executeUpdate()
		}
	}

	private fun insertSettlementEntry(
		connection: Connection,
		paymentId: Long,
		orderId: Long,
		saleId: Long,
		recipientUserId: Long,
	) {
		connection.prepareStatement(
			"""INSERT INTO settlement_entries (
				payment_id, order_id, sale_id, seller_id, recipient_user_id, quantity,
				unit_price, gross_amount, payment_approved_at, settlement_date, created_at
			) VALUES (?, ?, ?, 1, ?, 1, 35000, 35000, now(), DATE '2026-09-20', now())""",
		).use { statement ->
			statement.setLong(1, paymentId)
			statement.setLong(2, orderId)
			statement.setLong(3, saleId)
			statement.setLong(4, recipientUserId)
			statement.executeUpdate()
		}
	}

	private fun insertInventoryReservation(connection: Connection, saleId: Long, orderId: Long) {
		connection.prepareStatement(
			"""INSERT INTO inventory_reservations (
				id, sale_id, order_id, quantity, status, expires_at, created_at, updated_at
			) VALUES (?, ?, ?, 1, 'CONFIRMED', now() + interval '1 hour', now(), now())""",
		).use { statement ->
			statement.setObject(1, UUID.randomUUID())
			statement.setLong(2, saleId)
			statement.setLong(3, orderId)
			statement.executeUpdate()
		}
	}

	private fun insertSaleInventoryCounter(connection: Connection, saleId: Long) {
		connection.prepareStatement(
			"""INSERT INTO sale_inventory_counters (sale_id, committed_quantity, created_at, updated_at)
				VALUES (?, 1, now(), now())""",
		).use { statement ->
			statement.setLong(1, saleId)
			statement.executeUpdate()
		}
	}

	private fun count(connection: Connection, tableName: String): Int = scalarInt(connection, "SELECT count(*) FROM $tableName")

	private fun tableExists(connection: Connection, tableName: String): Boolean = connection.prepareStatement(
		"SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?)",
	).use { statement ->
		statement.setString(1, tableName)
		statement.executeQuery().use { result ->
			result.next()
			result.getBoolean(1)
		}
	}

	private fun scalarInt(connection: Connection, sql: String): Int = connection.prepareStatement(sql).use { statement ->
		statement.executeQuery().use { result ->
			result.next()
			result.getInt(1)
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
		const val SALES_SCHEMA = "inventory_simplification_sales_migration"
		const val ORDER_SCHEMA = "inventory_simplification_order_migration"
		const val CLEANUP_SCHEMA = "inventory_simplification_cleanup_migration"

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
