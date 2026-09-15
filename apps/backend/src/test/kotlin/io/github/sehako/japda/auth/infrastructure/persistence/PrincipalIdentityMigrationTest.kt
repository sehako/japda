package io.github.sehako.japda.auth.infrastructure.persistence

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
@DisplayName("인증 주체 도메인 연결 migration")
class PrincipalIdentityMigrationTest {
    @Test
    @DisplayName("과거 주문과 배송지의 최대 구매자 ID 다음부터 신규 ID를 발급한다")
    fun 과거_구매자_ID_최댓값_이후_발급() {
        migrateTo("9")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO $SCHEMA")
                statement.execute("INSERT INTO buyer_shipping_address_books (buyer_id, created_at) VALUES (1100, now())")
                statement.execute("INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now())")
                statement.execute("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES ('2026-09-14', 20, 1)")
                statement.execute("INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (1, 1, '2026-09-14', 35000, 10, now())")
                statement.execute("INSERT INTO orders (sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status, recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at) VALUES (1, 900, '550e8400-e29b-41d4-a716-446655440000', 'verified-order-900', 1, '상품', 35000, 35000, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', now(), now() + interval '3 minutes')")
            }
        }

        migrateTo("10")

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO $SCHEMA")
                statement.executeQuery("SELECT nextval('buyer_domain_id_seq')").use { result ->
                    result.next()
                    assertEquals(1101L, result.getLong(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM buyer_principal_identities").use { result ->
                    result.next()
                    assertEquals(0L, result.getLong(1))
                }
            }
        }
    }

    private fun migrateTo(version: String) {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(SCHEMA).target(MigrationVersion.fromVersion(version)).load().migrate()
    }

    private companion object {
        const val SCHEMA = "principal_identity_migration"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
