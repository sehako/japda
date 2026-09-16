package io.github.sehako.japda.settlement.infrastructure.persistence

import java.sql.DriverManager
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("판매자별 정산 migration")
class SellerSettlementMigrationTest {
	@BeforeAll
	fun migration_적용() {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SCHEMA)
			.load()
			.migrate()
	}

	@Test
	@DisplayName("정산 실행에 확정 상태와 확정 완료 시각을 추가한다")
	fun 정산_실행_확정_계약_추가() {
		val confirmationColumn = query(
			"""SELECT data_type, is_nullable
				FROM information_schema.columns
				WHERE table_schema = ? AND table_name = 'settlement_runs'
					AND column_name = 'confirmation_completed_at'""",
		) { result ->
			ConfirmationColumnDefinition(
				dataType = result.getString("data_type"),
				nullable = result.getString("is_nullable"),
			)
		}.single()

		assertEquals(ConfirmationColumnDefinition("timestamp with time zone", "YES"), confirmationColumn)

		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.autoCommit = false
			connection.prepareStatement(
				"""INSERT INTO $SCHEMA.settlement_runs
					(settlement_date, platform_fee_rate_bps, status, started_at, confirmation_completed_at, created_at)
					VALUES (DATE '2026-09-16', 1000, 'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
			).use { statement ->
				assertEquals(1, statement.executeUpdate())
			}
			connection.rollback()
		}
	}

	@Test
	@DisplayName("판매자별 정산 테이블의 컬럼을 생성한다")
	fun 판매자별_정산_테이블_컬럼_생성() {
		val columns = query(
			"""SELECT column_name, data_type, character_maximum_length, is_nullable, column_default, is_identity
				FROM information_schema.columns
				WHERE table_schema = ? AND table_name = 'seller_settlements'""",
		) { result ->
			ColumnDefinition(
				name = result.getString("column_name"),
				dataType = result.getString("data_type"),
				maximumLength = result.getInt("character_maximum_length").takeUnless { result.wasNull() },
				nullable = result.getString("is_nullable"),
				defaultValue = result.getString("column_default"),
				identity = result.getString("is_identity"),
			)
		}.associateBy { it.name }

		assertEquals(REQUIRED_COLUMNS.associateBy { it.name }, columns)
	}

	@Test
	@DisplayName("판매자별 정산 테이블의 제약과 참조 대상을 생성한다")
	fun 판매자별_정산_테이블_제약_생성() {
		val constraints = query(
			"""SELECT constraint_name, constraint_type
				FROM information_schema.table_constraints
				WHERE constraint_schema = ? AND table_name = 'seller_settlements'""",
		) { result ->
			ConstraintDefinition(
				name = result.getString("constraint_name"),
				type = result.getString("constraint_type"),
			)
		}.toSet()

		assertTrue(constraints.containsAll(REQUIRED_CONSTRAINTS))

		val foreignKeys = query(
			"""SELECT tc.constraint_name, kcu.column_name, ccu.table_name AS referenced_table,
					ccu.column_name AS referenced_column
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
					ON tc.constraint_schema = kcu.constraint_schema AND tc.constraint_name = kcu.constraint_name
				JOIN information_schema.constraint_column_usage ccu
					ON tc.constraint_schema = ccu.constraint_schema AND tc.constraint_name = ccu.constraint_name
				WHERE tc.constraint_schema = ? AND tc.table_name = 'seller_settlements'
					AND tc.constraint_type = 'FOREIGN KEY'""",
		) { result ->
			ForeignKeyDefinition(
				name = result.getString("constraint_name"),
				columnName = result.getString("column_name"),
				referencedTable = result.getString("referenced_table"),
				referencedColumn = result.getString("referenced_column"),
			)
		}.toSet()

		assertEquals(REQUIRED_FOREIGN_KEYS, foreignKeys)
	}

	@Test
	@DisplayName("정산 실행별 판매자 정산 조회 인덱스를 생성한다")
	fun 정산_실행별_판매자_정산_조회_인덱스_생성() {
		val indexColumns = query(
			"""SELECT attribute.attname AS column_name, index_definition.indisunique
				FROM pg_catalog.pg_class table_class
				JOIN pg_catalog.pg_namespace namespace ON namespace.oid = table_class.relnamespace
				JOIN pg_catalog.pg_index index_definition ON index_definition.indrelid = table_class.oid
				JOIN pg_catalog.pg_class index_class ON index_class.oid = index_definition.indexrelid
				JOIN LATERAL unnest(index_definition.indkey) WITH ORDINALITY AS indexed_column(attnum, position) ON true
				JOIN pg_catalog.pg_attribute attribute
					ON attribute.attrelid = table_class.oid AND attribute.attnum = indexed_column.attnum
				WHERE namespace.nspname = ? AND table_class.relname = 'seller_settlements'
					AND index_class.relname = 'seller_settlements_run_id_id_idx'
				ORDER BY indexed_column.position""",
		) { result ->
			IndexColumnDefinition(
				columnName = result.getString("column_name"),
				unique = result.getBoolean("indisunique"),
			)
		}

		assertEquals(
			listOf(
				IndexColumnDefinition("settlement_run_id", false),
				IndexColumnDefinition("id", false),
			),
			indexColumns,
		)
	}

	@Test
	@DisplayName("실행별 판매자 중복 결과를 거부한다")
	fun 실행별_판매자_중복_결과_거부() {
		withSettlementFixture { connection, runId, userId ->
			insertSellerSettlement(connection, runId, userId, netAmount = 90)

			assertFailsWith<SQLException> {
				insertSellerSettlement(connection, runId, userId, netAmount = 90)
			}
		}
	}

	@Test
	@DisplayName("총액과 수수료 및 입금 예정 금액이 일치하지 않으면 거부한다")
	fun 판매자별_정산_금액_불일치_거부() {
		withSettlementFixture { connection, runId, userId ->
			assertFailsWith<SQLException> {
				insertSellerSettlement(connection, runId, userId, netAmount = 91)
			}
		}
	}

	private fun withSettlementFixture(block: (Connection, Long, Long) -> Unit) {
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.autoCommit = false
			try {
				val userId = connection.prepareStatement(
					"""INSERT INTO $SCHEMA.users (provider, provider_subject, email, created_at)
						VALUES ('GOOGLE', 'seller', 'seller@example.com', CURRENT_TIMESTAMP) RETURNING id""",
				).use { statement ->
					statement.executeQuery().use { result ->
						result.next()
						result.getLong(1)
					}
				}
				val runId = connection.prepareStatement(
					"""INSERT INTO $SCHEMA.settlement_runs
						(settlement_date, platform_fee_rate_bps, status, started_at, collection_completed_at, created_at)
						VALUES (DATE '2026-09-15', 1000, 'COLLECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
						RETURNING id""",
				).use { statement ->
					statement.executeQuery().use { result ->
						result.next()
						result.getLong(1)
					}
				}
				block(connection, runId, userId)
			} finally {
				connection.rollback()
			}
		}
	}

	private fun insertSellerSettlement(connection: Connection, runId: Long, userId: Long, netAmount: Long) {
		connection.prepareStatement(
			"""INSERT INTO $SCHEMA.seller_settlements
				(settlement_run_id, seller_id, recipient_user_id, detail_count, gross_amount,
				 platform_fee_amount, net_amount, status, confirmed_at, created_at)
				VALUES (?, 1, ?, 1, 100, 10, ?, 'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
		).use { statement ->
			statement.setLong(1, runId)
			statement.setLong(2, userId)
			statement.setLong(3, netAmount)
			statement.executeUpdate()
		}
	}

	private fun <T> query(sql: String, rowMapper: (java.sql.ResultSet) -> T): List<T> =
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.prepareStatement(sql).use { statement ->
				statement.setString(1, SCHEMA)
				statement.executeQuery().use { result ->
					buildList {
						while (result.next()) add(rowMapper(result))
					}
				}
			}
		}

	private data class ConfirmationColumnDefinition(
		val dataType: String,
		val nullable: String,
	)

	private data class ColumnDefinition(
		val name: String,
		val dataType: String,
		val maximumLength: Int?,
		val nullable: String,
		val defaultValue: String?,
		val identity: String,
	)

	private data class ConstraintDefinition(
		val name: String,
		val type: String,
	)

	private data class ForeignKeyDefinition(
		val name: String,
		val columnName: String,
		val referencedTable: String,
		val referencedColumn: String,
	)

	private data class IndexColumnDefinition(
		val columnName: String,
		val unique: Boolean,
	)

	private companion object {
		const val SCHEMA = "seller_settlement_migration"

		val REQUIRED_COLUMNS = listOf(
			ColumnDefinition("id", "bigint", null, "NO", null, "YES"),
			ColumnDefinition("settlement_run_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("seller_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("recipient_user_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("detail_count", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("gross_amount", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("platform_fee_amount", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("net_amount", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("status", "character varying", 30, "NO", null, "NO"),
			ColumnDefinition("confirmed_at", "timestamp with time zone", null, "NO", null, "NO"),
			ColumnDefinition("created_at", "timestamp with time zone", null, "NO", null, "NO"),
		)

		val REQUIRED_CONSTRAINTS = setOf(
			ConstraintDefinition("seller_settlements_pkey", "PRIMARY KEY"),
			ConstraintDefinition("seller_settlements_run_fk", "FOREIGN KEY"),
			ConstraintDefinition("seller_settlements_recipient_user_fk", "FOREIGN KEY"),
			ConstraintDefinition("seller_settlements_run_seller_unique", "UNIQUE"),
			ConstraintDefinition("seller_settlements_seller_id_positive", "CHECK"),
			ConstraintDefinition("seller_settlements_detail_count_positive", "CHECK"),
			ConstraintDefinition("seller_settlements_gross_amount_positive", "CHECK"),
			ConstraintDefinition("seller_settlements_platform_fee_amount_non_negative", "CHECK"),
			ConstraintDefinition("seller_settlements_net_amount_non_negative", "CHECK"),
			ConstraintDefinition("seller_settlements_status_valid", "CHECK"),
			ConstraintDefinition("seller_settlements_amounts_consistent", "CHECK"),
		)

		val REQUIRED_FOREIGN_KEYS = setOf(
			ForeignKeyDefinition("seller_settlements_run_fk", "settlement_run_id", "settlement_runs", "id"),
			ForeignKeyDefinition("seller_settlements_recipient_user_fk", "recipient_user_id", "users", "id"),
		)

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
