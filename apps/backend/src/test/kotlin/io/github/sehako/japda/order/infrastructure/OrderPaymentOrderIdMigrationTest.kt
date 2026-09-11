package io.github.sehako.japda.order.infrastructure

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.DisplayName
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("주문 결제 주문 식별자 migration")
class OrderPaymentOrderIdMigrationTest {
	@Test
	@DisplayName("기존 주문은 legacy 식별자로 보정하고 결제 주문 식별자 제약을 적용한다")
	fun 기존_주문_legacy_식별자로_보정하고_결제_주문_식별자_제약을_적용한다() {
		migrateTo("5")
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("SET search_path TO $SCHEMA")
				statement.execute("INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now())")
				statement.execute("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES ('2026-09-11', 20, 1)")
				statement.execute("INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (1, 1, '2026-09-11', 35000, 10, now())")
				statement.execute(
					"INSERT INTO orders (sale_id, buyer_id, idempotency_key, quantity, product_name, unit_price, total_price, status, recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at) VALUES (1, 123, '550e8400-e29b-41d4-a716-446655440000', 2, '상품', 35000, 70000, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', now(), now() + interval '3 minutes')",
				)
			}
		}

		migrateTo("6")

		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("SET search_path TO $SCHEMA")
				statement.executeQuery("SELECT payment_order_id FROM orders WHERE id = 1").use { result ->
					result.next()
					assertEquals("legacy_1", result.getString("payment_order_id"))
				}
			}
		}
	}

	private fun migrateTo(version: String) {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SCHEMA)
			.target(MigrationVersion.fromVersion(version))
			.load()
			.migrate()
	}

	private companion object {
		const val SCHEMA = "payment_order_id_migration"

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
