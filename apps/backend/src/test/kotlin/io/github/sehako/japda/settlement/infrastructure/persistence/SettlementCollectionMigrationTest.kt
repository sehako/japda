package io.github.sehako.japda.settlement.infrastructure.persistence

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
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
@DisplayName("정산 수집 migration")
class SettlementCollectionMigrationTest {
	@BeforeAll
	fun migration_적용() {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SCHEMA)
			.load()
			.migrate()
	}

	@Test
	@DisplayName("정산 실행과 상세 테이블의 컬럼을 생성한다")
	fun 정산_수집_테이블_컬럼_생성() {
		val columns = query(
			"""SELECT table_name, column_name, data_type, character_maximum_length,
					is_nullable, column_default, is_identity
				FROM information_schema.columns
				WHERE table_schema = ? AND table_name IN ('settlement_runs', 'settlement_details')""",
		) { result ->
			ColumnDefinition(
				tableName = result.getString("table_name"),
				columnName = result.getString("column_name"),
				dataType = result.getString("data_type"),
				maximumLength = result.getInt("character_maximum_length").takeUnless { result.wasNull() },
				nullable = result.getString("is_nullable"),
				defaultValue = result.getString("column_default"),
				identity = result.getString("is_identity"),
			)
		}.associateBy { "${it.tableName}.${it.columnName}" }

		assertEquals(REQUIRED_COLUMNS.keys, columns.keys)
		REQUIRED_COLUMNS.forEach { (name, expected) ->
			assertEquals(expected, columns.getValue(name), name)
		}
	}

	@Test
	@DisplayName("정산 실행과 상세 테이블의 제약과 참조 대상을 생성한다")
	fun 정산_수집_테이블_제약_생성() {
		val constraints = query(
			"""SELECT table_name, constraint_name, constraint_type
				FROM information_schema.table_constraints
				WHERE constraint_schema = ? AND table_name IN ('settlement_runs', 'settlement_details')""",
		) { result ->
			ConstraintDefinition(
				tableName = result.getString("table_name"),
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
				WHERE tc.constraint_schema = ? AND tc.table_name = 'settlement_details'
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
	@DisplayName("정산 실행별 상세 조회 인덱스를 생성한다")
	fun 정산_실행별_상세_조회_인덱스_생성() {
		val indexColumns = query(
			"""SELECT a.attname AS column_name, index_definition.indisunique
				FROM pg_catalog.pg_class table_class
				JOIN pg_catalog.pg_namespace namespace ON namespace.oid = table_class.relnamespace
				JOIN pg_catalog.pg_index index_definition ON index_definition.indrelid = table_class.oid
				JOIN pg_catalog.pg_class index_class ON index_class.oid = index_definition.indexrelid
				JOIN LATERAL unnest(index_definition.indkey) WITH ORDINALITY AS indexed_column(attnum, position) ON true
				JOIN pg_catalog.pg_attribute a ON a.attrelid = table_class.oid AND a.attnum = indexed_column.attnum
				WHERE namespace.nspname = ? AND table_class.relname = 'settlement_details'
					AND index_class.relname = 'settlement_details_run_id_id_idx'
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
		val columnName: String,
		val dataType: String,
		val maximumLength: Int?,
		val nullable: String,
		val defaultValue: String?,
		val identity: String,
	)

	private data class ConstraintDefinition(
		val tableName: String,
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
		const val SCHEMA = "settlement_collection_migration"

		val REQUIRED_COLUMNS = listOf(
			ColumnDefinition("settlement_runs", "id", "bigint", null, "NO", null, "YES"),
			ColumnDefinition("settlement_runs", "settlement_date", "date", null, "NO", null, "NO"),
			ColumnDefinition("settlement_runs", "platform_fee_rate_bps", "integer", null, "NO", null, "NO"),
			ColumnDefinition("settlement_runs", "status", "character varying", 30, "NO", null, "NO"),
			ColumnDefinition("settlement_runs", "collected_count", "bigint", null, "NO", "0", "NO"),
			ColumnDefinition("settlement_runs", "collected_amount", "bigint", null, "NO", "0", "NO"),
			ColumnDefinition("settlement_runs", "started_at", "timestamp with time zone", null, "NO", null, "NO"),
			ColumnDefinition("settlement_runs", "collection_completed_at", "timestamp with time zone", null, "YES", null, "NO"),
			ColumnDefinition("settlement_runs", "created_at", "timestamp with time zone", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "id", "bigint", null, "NO", null, "YES"),
			ColumnDefinition("settlement_details", "settlement_run_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "payment_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "order_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "sale_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "seller_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "recipient_user_id", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "quantity", "integer", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "unit_price", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "gross_amount", "bigint", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "payment_approved_at", "timestamp with time zone", null, "NO", null, "NO"),
			ColumnDefinition("settlement_details", "created_at", "timestamp with time zone", null, "NO", null, "NO"),
		).associateBy { "${it.tableName}.${it.columnName}" }

		val REQUIRED_CONSTRAINTS = setOf(
			ConstraintDefinition("settlement_runs", "settlement_runs_pkey", "PRIMARY KEY"),
			ConstraintDefinition("settlement_runs", "settlement_runs_settlement_date_unique", "UNIQUE"),
			ConstraintDefinition("settlement_runs", "settlement_runs_platform_fee_rate_bps_range", "CHECK"),
			ConstraintDefinition("settlement_runs", "settlement_runs_status_valid", "CHECK"),
			ConstraintDefinition("settlement_runs", "settlement_runs_collected_count_non_negative", "CHECK"),
			ConstraintDefinition("settlement_runs", "settlement_runs_collected_amount_non_negative", "CHECK"),
			ConstraintDefinition("settlement_details", "settlement_details_pkey", "PRIMARY KEY"),
			ConstraintDefinition("settlement_details", "settlement_details_settlement_run_fk", "FOREIGN KEY"),
			ConstraintDefinition("settlement_details", "settlement_details_payment_fk", "FOREIGN KEY"),
			ConstraintDefinition("settlement_details", "settlement_details_order_fk", "FOREIGN KEY"),
			ConstraintDefinition("settlement_details", "settlement_details_sale_fk", "FOREIGN KEY"),
			ConstraintDefinition("settlement_details", "settlement_details_recipient_user_fk", "FOREIGN KEY"),
			ConstraintDefinition("settlement_details", "settlement_details_payment_unique", "UNIQUE"),
			ConstraintDefinition("settlement_details", "settlement_details_seller_id_positive", "CHECK"),
			ConstraintDefinition("settlement_details", "settlement_details_quantity_positive", "CHECK"),
			ConstraintDefinition("settlement_details", "settlement_details_unit_price_positive", "CHECK"),
			ConstraintDefinition("settlement_details", "settlement_details_gross_amount_positive", "CHECK"),
		)

		val REQUIRED_FOREIGN_KEYS = setOf(
			ForeignKeyDefinition("settlement_details_settlement_run_fk", "settlement_run_id", "settlement_runs", "id"),
			ForeignKeyDefinition("settlement_details_payment_fk", "payment_id", "payments", "id"),
			ForeignKeyDefinition("settlement_details_order_fk", "order_id", "orders", "id"),
			ForeignKeyDefinition("settlement_details_sale_fk", "sale_id", "sales", "id"),
			ForeignKeyDefinition("settlement_details_recipient_user_fk", "recipient_user_id", "users", "id"),
		)

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
