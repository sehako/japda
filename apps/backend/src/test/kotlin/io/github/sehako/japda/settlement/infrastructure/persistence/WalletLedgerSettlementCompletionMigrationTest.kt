package io.github.sehako.japda.settlement.infrastructure.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("지갑·원장과 정산 완료 migration")
class WalletLedgerSettlementCompletionMigrationTest {
	@BeforeAll
	fun migration_적용() {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SCHEMA)
			.load()
			.migrate()
	}

	@Test
	@DisplayName("지갑과 원장 테이블의 컬럼을 생성한다")
	fun 지갑과_원장_테이블_컬럼_생성() {
		val columns = query(
			"""SELECT table_name, column_name, data_type, character_maximum_length,
					is_nullable, column_default, is_identity
				FROM information_schema.columns
				WHERE table_schema = ? AND table_name IN ('wallets', 'ledger_entries')""",
		) { result ->
			ColumnDefinition(
				tableName = result.getString("table_name"),
				name = result.getString("column_name"),
				dataType = result.getString("data_type"),
				maximumLength = result.getInt("character_maximum_length").takeUnless { result.wasNull() },
				nullable = result.getString("is_nullable"),
				defaultValue = result.getString("column_default"),
				identity = result.getString("is_identity"),
			)
		}.associateBy { "${it.tableName}.${it.name}" }

		assertEquals(REQUIRED_COLUMNS.associateBy { "${it.tableName}.${it.name}" }, columns)
	}

	@Test
	@DisplayName("지갑과 원장의 유일성·외래 키·조회 인덱스를 생성한다")
	fun 지갑과_원장_키와_인덱스_생성() {
		val foreignKeys = query(
			"""SELECT tc.constraint_name, tc.table_name, kcu.column_name,
					ccu.table_name AS referenced_table, ccu.column_name AS referenced_column
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
					ON tc.constraint_schema = kcu.constraint_schema AND tc.constraint_name = kcu.constraint_name
				JOIN information_schema.constraint_column_usage ccu
					ON tc.constraint_schema = ccu.constraint_schema AND tc.constraint_name = ccu.constraint_name
				WHERE tc.constraint_schema = ? AND tc.table_name IN ('wallets', 'ledger_entries')
					AND tc.constraint_type = 'FOREIGN KEY'""",
		) { result ->
			ForeignKeyDefinition(
				name = result.getString("constraint_name"),
				tableName = result.getString("table_name"),
				columnName = result.getString("column_name"),
				referencedTable = result.getString("referenced_table"),
				referencedColumn = result.getString("referenced_column"),
			)
		}.toSet()

		assertEquals(REQUIRED_FOREIGN_KEYS, foreignKeys)
		assertEquals(listOf("user_id"), indexColumns("wallets", "wallets_user_id_unique", unique = true))
		assertEquals(
			listOf("source_type", "source_id"),
			indexColumns("ledger_entries", "ledger_entries_source_unique", unique = true),
		)
		assertEquals(
			listOf("wallet_id", "id"),
			indexColumns("ledger_entries", "ledger_entries_wallet_id_id_idx", unique = false),
		)
	}

	@Test
	@DisplayName("지갑 잔액과 원장 금액 및 반영 후 잔액의 범위를 제한한다")
	fun 지갑과_원장_금액_범위_제한() {
		withLedgerFixture { connection, userId ->
			assertConstraintViolation(connection) { insertWallet(connection, userId, -1) }
			val walletId = insertWallet(connection, userId, 0)
			assertConstraintViolation(connection) {
				insertLedgerEntry(connection, walletId, "CREDIT", 0, 0, "SELLER_SETTLEMENT", 1)
			}
			assertConstraintViolation(connection) {
				insertLedgerEntry(connection, walletId, "CREDIT", 1, -1, "SELLER_SETTLEMENT", 1)
			}
			assertConstraintViolation(connection) {
				insertLedgerEntry(connection, walletId, "CREDIT", 1, 1, "SELLER_SETTLEMENT", 0)
			}
		}
	}

	@Test
	@DisplayName("원장 방향·source 종류·정산 source 방향 조합을 제한한다")
	fun 원장_방향과_source_조합_제한() {
		withLedgerFixture { connection, userId ->
			val walletId = insertWallet(connection, userId, 0)
			assertConstraintViolation(connection) {
				insertLedgerEntry(connection, walletId, "INVALID", 1, 1, "SELLER_SETTLEMENT", 1)
			}
			assertConstraintViolation(connection) {
				insertLedgerEntry(connection, walletId, "CREDIT", 1, 1, "INVALID", 1)
			}
			assertConstraintViolation(connection) {
				insertLedgerEntry(connection, walletId, "DEBIT", 1, 0, "SELLER_SETTLEMENT", 1)
			}
		}
	}

	@Test
	@DisplayName("사용자별 지갑과 업무 source별 원장을 하나로 제한한다")
	fun 지갑과_원장_중복_제한() {
		withLedgerFixture { connection, userId ->
			val walletId = insertWallet(connection, userId, 0)
			assertConstraintViolation(connection) { insertWallet(connection, userId, 0) }
			insertLedgerEntry(connection, walletId, "CREDIT", 10, 10, "SELLER_SETTLEMENT", 1)
			assertConstraintViolation(connection) {
				insertLedgerEntry(connection, walletId, "CREDIT", 10, 20, "SELLER_SETTLEMENT", 1)
			}
		}
	}

	@Test
	@DisplayName("판매자별 정산의 입금 상태와 시각을 일치시킨다")
	fun 판매자별_정산_입금_상태와_시각_일치() {
		withSettlementFixture { connection, runId, userId ->
			insertSellerSettlement(connection, runId, userId, "CONFIRMED", credited = false)
			insertSellerSettlement(connection, runId, userId, "CREDITED", credited = true, sellerId = 2)
			assertConstraintViolation(connection) {
				insertSellerSettlement(connection, runId, userId, "CONFIRMED", credited = true, sellerId = 3)
			}
			assertConstraintViolation(connection) {
				insertSellerSettlement(connection, runId, userId, "CREDITED", credited = false, sellerId = 4)
			}
		}
	}

	@Test
	@DisplayName("정산 실행의 완료 상태와 시각을 일치시킨다")
	fun 정산_실행_완료_상태와_시각_일치() {
		withConnection { connection ->
			insertSettlementRun(connection, "CONFIRMED", completed = false, day = 1)
			insertSettlementRun(connection, "COMPLETED", completed = true, day = 2)
			assertConstraintViolation(connection) {
				insertSettlementRun(connection, "CONFIRMED", completed = true, day = 3)
			}
			assertConstraintViolation(connection) {
				insertSettlementRun(connection, "COMPLETED", completed = false, day = 4)
			}
		}
	}

	private fun assertConstraintViolation(connection: Connection, block: () -> Unit) {
		val savepoint = connection.setSavepoint()
		try {
			assertFailsWith<SQLException> { block() }
		} finally {
			connection.rollback(savepoint)
		}
	}

	private fun indexColumns(tableName: String, indexName: String, unique: Boolean): List<String> {
		val rows = query(
			"""SELECT attribute.attname AS column_name, index_definition.indisunique
				FROM pg_catalog.pg_class table_class
				JOIN pg_catalog.pg_namespace namespace ON namespace.oid = table_class.relnamespace
				JOIN pg_catalog.pg_index index_definition ON index_definition.indrelid = table_class.oid
				JOIN pg_catalog.pg_class index_class ON index_class.oid = index_definition.indexrelid
				JOIN LATERAL unnest(index_definition.indkey) WITH ORDINALITY AS indexed_column(attnum, position) ON true
				JOIN pg_catalog.pg_attribute attribute
					ON attribute.attrelid = table_class.oid AND attribute.attnum = indexed_column.attnum
				WHERE namespace.nspname = ? AND table_class.relname = '$tableName'
					AND index_class.relname = '$indexName'
				ORDER BY indexed_column.position""",
		) { result -> result.getString("column_name") to result.getBoolean("indisunique") }
		assertEquals(List(rows.size) { unique }, rows.map { it.second })
		return rows.map { it.first }
	}

	private fun withLedgerFixture(block: (Connection, Long) -> Unit) = withConnection { connection ->
		val userId = insertUser(connection)
		block(connection, userId)
	}

	private fun withSettlementFixture(block: (Connection, Long, Long) -> Unit) = withConnection { connection ->
		val userId = insertUser(connection)
		val runId = insertSettlementRun(connection, "CONFIRMED", completed = false, day = 10)
		block(connection, runId, userId)
	}

	private fun withConnection(block: (Connection) -> Unit) {
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.autoCommit = false
			try {
				block(connection)
			} finally {
				connection.rollback()
			}
		}
	}

	private fun insertUser(connection: Connection): Long = connection.prepareStatement(
		"""INSERT INTO $SCHEMA.users (provider, provider_subject, email, created_at)
			VALUES ('GOOGLE', 'wallet-owner', 'wallet-owner@example.com', CURRENT_TIMESTAMP) RETURNING id""",
	).use { statement ->
		statement.executeQuery().use { result ->
			result.next()
			result.getLong(1)
		}
	}

	private fun insertWallet(connection: Connection, userId: Long, balance: Long): Long = connection.prepareStatement(
		"""INSERT INTO $SCHEMA.wallets (user_id, balance, created_at, updated_at)
			VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
	).use { statement ->
		statement.setLong(1, userId)
		statement.setLong(2, balance)
		statement.executeQuery().use { result ->
			result.next()
			result.getLong(1)
		}
	}

	private fun insertLedgerEntry(
		connection: Connection,
		walletId: Long,
		direction: String,
		amount: Long,
		balanceAfter: Long,
		sourceType: String,
		sourceId: Long,
	) {
		connection.prepareStatement(
			"""INSERT INTO $SCHEMA.ledger_entries
				(wallet_id, direction, amount, balance_after, source_type, source_id, created_at)
				VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)""",
		).use { statement ->
			statement.setLong(1, walletId)
			statement.setString(2, direction)
			statement.setLong(3, amount)
			statement.setLong(4, balanceAfter)
			statement.setString(5, sourceType)
			statement.setLong(6, sourceId)
			statement.executeUpdate()
		}
	}

	private fun insertSettlementRun(connection: Connection, status: String, completed: Boolean, day: Int): Long =
		connection.prepareStatement(
			"""INSERT INTO $SCHEMA.settlement_runs
				(settlement_date, platform_fee_rate_bps, status, started_at, collection_completed_at,
				 confirmation_completed_at, completed_at, created_at)
				VALUES (DATE '2026-08-01' + ?, 1000, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
				 CURRENT_TIMESTAMP, ${if (completed) "CURRENT_TIMESTAMP" else "NULL"}, CURRENT_TIMESTAMP) RETURNING id""",
		).use { statement ->
			statement.setInt(1, day)
			statement.setString(2, status)
			statement.executeQuery().use { result ->
				result.next()
				result.getLong(1)
			}
		}

	private fun insertSellerSettlement(
		connection: Connection,
		runId: Long,
		userId: Long,
		status: String,
		credited: Boolean,
		sellerId: Long = 1,
	) {
		connection.prepareStatement(
			"""INSERT INTO $SCHEMA.seller_settlements
				(settlement_run_id, seller_id, recipient_user_id, detail_count, gross_amount,
				 platform_fee_amount, net_amount, status, confirmed_at, credited_at, created_at)
				VALUES (?, ?, ?, 1, 100, 10, 90, ?, CURRENT_TIMESTAMP,
				 ${if (credited) "CURRENT_TIMESTAMP" else "NULL"}, CURRENT_TIMESTAMP)""",
		).use { statement ->
			statement.setLong(1, runId)
			statement.setLong(2, sellerId)
			statement.setLong(3, userId)
			statement.setString(4, status)
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

	private data class ColumnDefinition(
		val tableName: String,
		val name: String,
		val dataType: String,
		val maximumLength: Int?,
		val nullable: String,
		val defaultValue: String?,
		val identity: String,
	)

	private data class ForeignKeyDefinition(
		val name: String,
		val tableName: String,
		val columnName: String,
		val referencedTable: String,
		val referencedColumn: String,
	)

	private companion object {
		const val SCHEMA = "wallet_ledger_settlement_completion_migration"

		val REQUIRED_COLUMNS = listOf(
			ColumnDefinition("wallets", "id", "bigint", null, "NO", null, "YES"),
			ColumnDefinition("wallets", "user_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("wallets", "balance", "bigint", null, "NO", "0", "NO"),
			ColumnDefinition("wallets", "created_at", "timestamp with time zone", null, "NO", null, "NO"),
			ColumnDefinition("wallets", "updated_at", "timestamp with time zone", null, "NO", null, "NO"),
			ColumnDefinition("ledger_entries", "id", "bigint", null, "NO", null, "YES"),
			ColumnDefinition("ledger_entries", "wallet_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("ledger_entries", "direction", "character varying", 20, "NO", null, "NO"),
			ColumnDefinition("ledger_entries", "amount", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("ledger_entries", "balance_after", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("ledger_entries", "source_type", "character varying", 50, "NO", null, "NO"),
			ColumnDefinition("ledger_entries", "source_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("ledger_entries", "created_at", "timestamp with time zone", null, "NO", null, "NO"),
		)

		val REQUIRED_FOREIGN_KEYS = setOf(
			ForeignKeyDefinition("wallets_user_fk", "wallets", "user_id", "users", "id"),
			ForeignKeyDefinition("ledger_entries_wallet_fk", "ledger_entries", "wallet_id", "wallets", "id"),
		)

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
