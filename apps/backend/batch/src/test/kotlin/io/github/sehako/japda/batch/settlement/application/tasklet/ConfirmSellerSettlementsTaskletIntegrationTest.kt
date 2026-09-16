package io.github.sehako.japda.batch.settlement.application.tasklet

import io.github.sehako.japda.batch.settlement.exception.SettlementConfirmationErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementConfirmationException
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.JobInstance
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.scope.context.StepContext
import org.springframework.batch.core.step.StepExecution
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("판매자 정산 검산·확정 tasklet")
class ConfirmSellerSettlementsTaskletIntegrationTest {
	private lateinit var jdbcTemplate: JdbcTemplate
	private lateinit var transactionTemplate: TransactionTemplate
	private lateinit var tasklet: ConfirmSellerSettlementsTasklet

	@BeforeAll
	fun 스키마와_대상을_준비한다() {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SCHEMA)
			.locations("filesystem:${System.getProperty("rootMigrationDirectory")}")
			.load()
			.migrate()
		val dataSource = DriverManagerDataSource(postgres.jdbcUrl.withCurrentSchema(SCHEMA), postgres.username, postgres.password)
		jdbcTemplate = JdbcTemplate(dataSource)
		transactionTemplate = TransactionTemplate(JdbcTransactionManager(dataSource))
		tasklet = ConfirmSellerSettlementsTasklet(
			SettlementRunJdbcRepository(jdbcTemplate),
			SellerSettlementJdbcRepository(jdbcTemplate),
			Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC),
		)
	}

	@AfterAll
	fun 스키마를_정리한다() {
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE") }
		}
	}

	@BeforeEach
	fun 데이터를_정리한다() {
		jdbcTemplate.execute(
			"""
			TRUNCATE TABLE seller_settlements, settlement_details, settlement_runs, payments, orders,
				seller_principal_identities, buyer_principal_identities, user_roles, users,
				sales, sale_days, products RESTART IDENTITY CASCADE
			""".trimIndent(),
		)
	}

	@Test
	@DisplayName("판매자별 합계에 수수료를 한 번 적용하고 확정한다")
	fun 판매자별_합계에_수수료를_한_번_적용하고_확정한다() {
		val runId = insertRun(feeRateBps = 3_333, collectedCount = 3, collectedAmount = 302)
		val recipient1 = insertUser()
		val recipient2 = insertUser()
		insertDetail(runId, sellerId = 11, recipientUserId = recipient1, grossAmount = 101)
		insertDetail(runId, sellerId = 11, recipientUserId = recipient1, grossAmount = 100)
		insertDetail(runId, sellerId = 12, recipientUserId = recipient2, grossAmount = 101)

		executeInTransaction(runId)

		val results = jdbcTemplate.queryForList("SELECT * FROM seller_settlements ORDER BY seller_id")
		assertEquals(2, results.size)
		assertSettlement(results[0], sellerId = 11, recipient1, count = 2, gross = 201, fee = 66, net = 135)
		assertSettlement(results[1], sellerId = 12, recipient2, count = 1, gross = 101, fee = 33, net = 68)
		val run = jdbcTemplate.queryForMap("SELECT * FROM settlement_runs WHERE id = ?", runId)
		assertEquals("CONFIRMED", run["status"])
		assertEquals(CONFIRMED_AT, (run["confirmation_completed_at"] as java.sql.Timestamp).toInstant())
	}

	@Test
	@DisplayName("0과 10000 수수료율 경계를 BIGINT 곱셈 overflow 없이 확정한다")
	fun 수수료율_경계를_BIGINT_곱셈_overflow_없이_확정한다() {
		val zeroRunId = insertRun(feeRateBps = 0, collectedCount = 1, collectedAmount = Long.MAX_VALUE)
		insertDetail(zeroRunId, sellerId = 21, recipientUserId = insertUser(), grossAmount = Long.MAX_VALUE)
		executeInTransaction(zeroRunId)
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT platform_fee_amount FROM seller_settlements WHERE settlement_run_id = ?", Long::class.java, zeroRunId))
		assertEquals(Long.MAX_VALUE, jdbcTemplate.queryForObject("SELECT net_amount FROM seller_settlements WHERE settlement_run_id = ?", Long::class.java, zeroRunId))

		val fullRunId = insertRun(feeRateBps = 10_000, collectedCount = 1, collectedAmount = Long.MAX_VALUE)
		insertDetail(fullRunId, sellerId = 22, recipientUserId = insertUser(), grossAmount = Long.MAX_VALUE)
		executeInTransaction(fullRunId)
		assertEquals(Long.MAX_VALUE, jdbcTemplate.queryForObject("SELECT platform_fee_amount FROM seller_settlements WHERE settlement_run_id = ?", Long::class.java, fullRunId))
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT net_amount FROM seller_settlements WHERE settlement_run_id = ?", Long::class.java, fullRunId))
	}

	@Test
	@DisplayName("같은 판매자의 지급 대상이 다르면 전체 transaction을 rollback한다")
	fun 같은_판매자의_지급_대상이_다르면_전체_transaction을_rollback한다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 2, collectedAmount = 200)
		insertDetail(runId, sellerId = 31, recipientUserId = insertUser(), grossAmount = 100)
		insertDetail(runId, sellerId = 31, recipientUserId = insertUser(), grossAmount = 100)

		val error = assertFailsWith<SettlementConfirmationException> { executeInTransaction(runId) }

		assertEquals(SettlementConfirmationErrorType.RECIPIENT_MISMATCH, error.errorType)
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
		assertEquals("COLLECTED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs WHERE id = ?", String::class.java, runId))
	}

	@Test
	@DisplayName("확정 transaction은 완료될 때까지 정산 상세 변경을 잠근다")
	fun 확정_transaction은_완료될_때까지_정산_상세_변경을_잠근다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 1, collectedAmount = 100)
		insertDetail(runId, sellerId = 35, recipientUserId = insertUser(), grossAmount = 100)
		val otherRecipientId = insertUser()
		val confirmationFinished = CountDownLatch(1)
		val allowCommit = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		val confirmation = executor.submit {
			transactionTemplate.executeWithoutResult {
				executeTasklet(runId)
				confirmationFinished.countDown()
				check(allowCommit.await(5, TimeUnit.SECONDS))
			}
		}

		try {
			check(confirmationFinished.await(5, TimeUnit.SECONDS))
			assertFailsWith<SQLException> {
				DriverManager.getConnection(postgres.jdbcUrl.withCurrentSchema(SCHEMA), postgres.username, postgres.password).use { connection ->
					connection.createStatement().use { it.execute("SET lock_timeout = '250ms'") }
					connection.prepareStatement(
						"UPDATE settlement_details SET recipient_user_id = ? WHERE settlement_run_id = ?",
					).use { statement ->
						statement.setLong(1, otherRecipientId)
						statement.setLong(2, runId)
						statement.executeUpdate()
					}
				}
			}
		} finally {
			allowCommit.countDown()
			confirmation.get(5, TimeUnit.SECONDS)
			executor.shutdownNow()
		}
	}

	@Test
	@DisplayName("확정 재검산 transaction은 완료될 때까지 판매자별 결과 변경을 잠근다")
	fun 확정_재검산_transaction은_완료될_때까지_판매자별_결과_변경을_잠근다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 1, collectedAmount = 100)
		insertDetail(runId, sellerId = 36, recipientUserId = insertUser(), grossAmount = 100)
		executeInTransaction(runId)
		val otherRecipientId = insertUser()
		val validationFinished = CountDownLatch(1)
		val allowCommit = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		val validation = executor.submit {
			transactionTemplate.executeWithoutResult {
				executeTasklet(runId)
				validationFinished.countDown()
				check(allowCommit.await(5, TimeUnit.SECONDS))
			}
		}

		try {
			check(validationFinished.await(5, TimeUnit.SECONDS))
			assertFailsWith<SQLException> {
				DriverManager.getConnection(postgres.jdbcUrl.withCurrentSchema(SCHEMA), postgres.username, postgres.password).use { connection ->
					connection.createStatement().use { it.execute("SET lock_timeout = '250ms'") }
					connection.prepareStatement(
						"UPDATE seller_settlements SET recipient_user_id = ? WHERE settlement_run_id = ?",
					).use { statement ->
						statement.setLong(1, otherRecipientId)
						statement.setLong(2, runId)
						statement.executeUpdate()
					}
				}
			}
		} finally {
			allowCommit.countDown()
			validation.get(5, TimeUnit.SECONDS)
			executor.shutdownNow()
		}
	}

	@Test
	@DisplayName("결과 저장 뒤 상태 전환이 실패하면 실제 transaction이 insert를 rollback한다")
	fun 결과_저장_뒤_상태_전환이_실패하면_실제_transaction이_insert를_rollback한다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 1, collectedAmount = 100)
		insertDetail(runId, sellerId = 35, recipientUserId = insertUser(), grossAmount = 100)
		jdbcTemplate.execute(
			"""
			CREATE OR REPLACE FUNCTION reject_settlement_confirmation() RETURNS trigger AS ${'$'}${'$'}
			BEGIN
				IF NEW.status = 'CONFIRMED' THEN RAISE EXCEPTION '확정 상태 전환 실패'; END IF;
				RETURN NEW;
			END;
			${'$'}${'$'} LANGUAGE plpgsql
			""".trimIndent(),
		)
		jdbcTemplate.execute(
			"CREATE TRIGGER reject_settlement_confirmation BEFORE UPDATE ON settlement_runs FOR EACH ROW EXECUTE FUNCTION reject_settlement_confirmation()",
		)

		try {
			val error = assertFailsWith<SettlementConfirmationException> { executeInTransaction(runId) }
			assertEquals(SettlementConfirmationErrorType.STATE_TRANSITION_FAILED, error.errorType)
			assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
			assertEquals("COLLECTED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs WHERE id = ?", String::class.java, runId))
		} finally {
			jdbcTemplate.execute("DROP TRIGGER reject_settlement_confirmation ON settlement_runs")
			jdbcTemplate.execute("DROP FUNCTION reject_settlement_confirmation()")
		}
	}

	@Test
	@DisplayName("수집 집계가 실제 상세와 다르면 확정하지 않는다")
	fun 수집_집계가_실제_상세와_다르면_확정하지_않는다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 1, collectedAmount = 101)
		insertDetail(runId, sellerId = 41, recipientUserId = insertUser(), grossAmount = 100)

		val error = assertFailsWith<SettlementConfirmationException> { executeInTransaction(runId) }

		assertEquals(SettlementConfirmationErrorType.DETAIL_AGGREGATE_MISMATCH, error.errorType)
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
	}

	@Test
	@DisplayName("빈 대상은 결과 행 없이 확정한다")
	fun 빈_대상은_결과_행_없이_확정한다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 0, collectedAmount = 0)

		executeInTransaction(runId)

		assertEquals("CONFIRMED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs WHERE id = ?", String::class.java, runId))
		assertNotNull(jdbcTemplate.queryForObject("SELECT confirmation_completed_at FROM settlement_runs WHERE id = ?", java.time.OffsetDateTime::class.java, runId))
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
	}

	@Test
	@DisplayName("COLLECTED 실행에 기존 결과가 있으면 덮어쓰지 않고 실패한다")
	fun COLLECTED_실행에_기존_결과가_있으면_덮어쓰지_않고_실패한다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 1, collectedAmount = 100)
		val recipientId = insertUser()
		insertDetail(runId, sellerId = 51, recipientUserId = recipientId, grossAmount = 100)
		insertSellerSettlement(runId, sellerId = 51, recipientId, gross = 100, fee = 5, net = 95)

		val error = assertFailsWith<SettlementConfirmationException> { executeInTransaction(runId) }

		assertEquals(SettlementConfirmationErrorType.EXISTING_RESULT, error.errorType)
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
		assertEquals("COLLECTED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs WHERE id = ?", String::class.java, runId))
	}

	@Test
	@DisplayName("이미 확정된 동일 결과는 insert 없이 성공하고 변조된 결과는 실패한다")
	fun 이미_확정된_동일_결과는_insert_없이_성공하고_변조된_결과는_실패한다() {
		val runId = insertRun(feeRateBps = 500, collectedCount = 1, collectedAmount = 100)
		val recipientId = insertUser()
		insertDetail(runId, sellerId = 61, recipientUserId = recipientId, grossAmount = 100)
		executeInTransaction(runId)
		val resultId = jdbcTemplate.queryForObject("SELECT id FROM seller_settlements WHERE settlement_run_id = ?", Long::class.java, runId)
		val otherRunId = insertRun(feeRateBps = 100, collectedCount = 1, collectedAmount = 50)
		insertDetail(otherRunId, sellerId = 62, recipientUserId = insertUser(), grossAmount = 50)
		executeInTransaction(otherRunId)

		executeInTransaction(runId)

		assertEquals(resultId, jdbcTemplate.queryForObject("SELECT id FROM seller_settlements WHERE settlement_run_id = ?", Long::class.java, runId))
		jdbcTemplate.update("UPDATE seller_settlements SET recipient_user_id = ? WHERE settlement_run_id = ?", insertUser(), runId)
		val error = assertFailsWith<SettlementConfirmationException> { executeInTransaction(runId) }
		assertEquals(SettlementConfirmationErrorType.CONFIRMED_RESULT_MISMATCH, error.errorType)
	}

	@Test
	@DisplayName("없는 실행과 확정할 수 없는 상태는 실패한다")
	fun 없는_실행과_확정할_수_없는_상태는_실패한다() {
		val missing = assertFailsWith<SettlementConfirmationException> { executeInTransaction(999_999) }
		assertEquals(SettlementConfirmationErrorType.RUN_NOT_FOUND, missing.errorType)
		val collectingRunId = insertRun(feeRateBps = 500, collectedCount = 0, collectedAmount = 0, status = "COLLECTING")

		val invalidState = assertFailsWith<SettlementConfirmationException> { executeInTransaction(collectingRunId) }

		assertEquals(SettlementConfirmationErrorType.INVALID_RUN_STATUS, invalidState.errorType)
	}

	private fun executeInTransaction(settlementRunId: Long) {
		transactionTemplate.executeWithoutResult {
			executeTasklet(settlementRunId)
		}
	}

	private fun executeTasklet(settlementRunId: Long) {
		val jobExecution = JobExecution(1, JobInstance(1, "testJob"), JobParameters())
		jobExecution.executionContext.putLong(PrepareSettlementRunTasklet.SETTLEMENT_RUN_ID_CONTEXT_KEY, settlementRunId)
		val stepExecution = StepExecution(1, "confirmSellerSettlementsStep", jobExecution)
		tasklet.execute(stepExecution.createStepContribution(), ChunkContext(StepContext(stepExecution)))
	}

	private fun insertRun(feeRateBps: Int, collectedCount: Long, collectedAmount: Long, status: String = "COLLECTED"): Long =
		jdbcTemplate.queryForObject(
			"""
			INSERT INTO settlement_runs (
				settlement_date, platform_fee_rate_bps, status, collected_count, collected_amount,
				started_at, collection_completed_at, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
			""".trimIndent(),
			Long::class.java,
			LocalDate.of(2026, 1, 1).plusDays(nextDateOffset++), feeRateBps, status, collectedCount, collectedAmount,
			CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC),
		)!!

	private fun insertDetail(runId: Long, sellerId: Long, recipientUserId: Long, grossAmount: Long) {
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (?, '상품', 'DRAFT', ?) RETURNING id",
			Long::class.java, sellerId, CREATED_AT.atOffset(ZoneOffset.UTC),
		)!!
		val saleDate = LocalDate.of(2026, 1, 1).plusDays(productId)
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 100, 1) ON CONFLICT DO NOTHING", saleDate)
		val saleId = jdbcTemplate.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, ?, ?, 1, 1, ?) RETURNING id",
			Long::class.java, productId, sellerId, saleDate, CREATED_AT.atOffset(ZoneOffset.UTC),
		)!!
		val orderId = jdbcTemplate.queryForObject(
			"""
			INSERT INTO orders (sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price,
				total_price, status, recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at)
			VALUES (?, 1, ?, ?, 1, '상품', ?, ?, 'PAID', '수령인', '010-0000-0000', '00000', '주소', '상세', ?, ?) RETURNING id
			""".trimIndent(),
			Long::class.java, saleId, UUID.randomUUID(), "order-${UUID.randomUUID()}", grossAmount, grossAmount,
			CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.plusSeconds(600).atOffset(ZoneOffset.UTC),
		)!!
		val paymentId = jdbcTemplate.queryForObject(
			"INSERT INTO payments (order_id, payment_key, toss_idempotency_key, status, requested_amount, created_at, approved_at) VALUES (?, ?, ?, 'APPROVED', ?, ?, ?) RETURNING id",
			Long::class.java, orderId, "payment-${UUID.randomUUID()}", UUID.randomUUID().toString(), grossAmount,
			CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC),
		)!!
		jdbcTemplate.update(
			"""
			INSERT INTO settlement_details (settlement_run_id, payment_id, order_id, sale_id, seller_id,
				recipient_user_id, quantity, unit_price, gross_amount, payment_approved_at, created_at)
			VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
			""".trimIndent(),
			runId, paymentId, orderId, saleId, sellerId, recipientUserId, grossAmount, grossAmount,
			CREATED_AT.atOffset(ZoneOffset.UTC), CREATED_AT.atOffset(ZoneOffset.UTC),
		)
	}

	private fun insertUser(): Long = jdbcTemplate.queryForObject(
		"INSERT INTO users (provider, provider_subject, email, created_at) VALUES ('GOOGLE', ?, ?, ?) RETURNING id",
		Long::class.java, UUID.randomUUID().toString(), "${UUID.randomUUID()}@example.com", CREATED_AT.atOffset(ZoneOffset.UTC),
	)!!

	private fun insertSellerSettlement(runId: Long, sellerId: Long, recipientId: Long, gross: Long, fee: Long, net: Long) {
		jdbcTemplate.update(
			"""
			INSERT INTO seller_settlements (settlement_run_id, seller_id, recipient_user_id, detail_count,
				gross_amount, platform_fee_amount, net_amount, status, confirmed_at, created_at)
			VALUES (?, ?, ?, 1, ?, ?, ?, 'CONFIRMED', ?, ?)
			""".trimIndent(),
			runId, sellerId, recipientId, gross, fee, net,
			CONFIRMED_AT.atOffset(ZoneOffset.UTC), CONFIRMED_AT.atOffset(ZoneOffset.UTC),
		)
	}

	private fun assertSettlement(row: Map<String, Any?>, sellerId: Long, recipientId: Long, count: Long, gross: Long, fee: Long, net: Long) {
		assertEquals(sellerId, (row["seller_id"] as Number).toLong())
		assertEquals(recipientId, (row["recipient_user_id"] as Number).toLong())
		assertEquals(count, (row["detail_count"] as Number).toLong())
		assertEquals(gross, (row["gross_amount"] as Number).toLong())
		assertEquals(fee, (row["platform_fee_amount"] as Number).toLong())
		assertEquals(net, (row["net_amount"] as Number).toLong())
	}

	private fun String.withCurrentSchema(schema: String): String =
		this + if (contains('?')) "&currentSchema=$schema" else "?currentSchema=$schema"

	private companion object {
		const val SCHEMA = "seller_settlement_confirmation"
		val CREATED_AT: Instant = Instant.parse("2026-09-16T00:00:00Z")
		val CONFIRMED_AT: Instant = Instant.parse("2026-09-16T01:00:00Z")
		var nextDateOffset = 0L

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
