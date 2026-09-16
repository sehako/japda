package io.github.sehako.japda.batch.settlement

import io.github.sehako.japda.batch.BatchApplication
import io.github.sehako.japda.batch.settlement.application.tasklet.CompleteSettlementCollectionTasklet
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStateException
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.scope.context.StepContext
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("판매자 일일 정산 Job")
class DailySellerSettlementJobIntegrationTest {
	private lateinit var context: ConfigurableApplicationContext
	private lateinit var jdbcTemplate: JdbcTemplate
	private lateinit var jobOperator: JobOperator
	private lateinit var job: Job

	@BeforeAll
	fun 애플리케이션을_시작한다() {
		Flyway.configure()
			.dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			.schemas(SCHEMA)
			.locations("filesystem:${System.getProperty("rootMigrationDirectory")}")
			.load()
			.migrate()

		context = SpringApplicationBuilder(BatchApplication::class.java)
			.web(WebApplicationType.NONE)
			.run(
				"--spring.datasource.url=${postgres.jdbcUrl.withCurrentSchema(SCHEMA)}",
				"--spring.datasource.username=${postgres.username}",
				"--spring.datasource.password=${postgres.password}",
				"--spring.batch.job.enabled=false",
			)
		jdbcTemplate = context.getBean(JdbcTemplate::class.java)
		jobOperator = context.getBean(JobOperator::class.java)
		job = context.getBean("dailySellerSettlementJob", Job::class.java)
	}

	@AfterAll
	fun 애플리케이션과_스키마를_정리한다() {
		if (::context.isInitialized) {
			context.close()
		}
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
			}
		}
	}

	@BeforeEach
	fun 데이터를_정리한다() {
		jdbcTemplate.execute(
			"""
			TRUNCATE TABLE
				ledger_entries, wallets, seller_settlements, settlement_details, settlement_runs, payments, orders,
				seller_principal_identities, buyer_principal_identities, user_roles, users,
				sales, sale_days, products,
				batch_step_execution_context, batch_job_execution_context,
				batch_step_execution, batch_job_execution_params,
				batch_job_execution, batch_job_instance
			RESTART IDENTITY CASCADE
			""".trimIndent(),
		)
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_instance", Long::class.java))
	}

	@Test
	@DisplayName("대상이 없으면 판매자별 결과 없이 확정을 완료한다")
	fun 대상이_없으면_판매자별_결과_없이_확정을_완료한다() {
		val execution = launch(EMPTY_SETTLEMENT_DATE, 250L)

		assertEquals(BatchStatus.COMPLETED, execution.status)
		assertEquals(
			listOf(
				"prepareSettlementRunStep",
				"collectSettlementDetailsStep",
				"completeSettlementCollectionStep",
				"confirmSellerSettlementsStep",
				"creditSellerWalletsStep",
				"completeSettlementRunStep",
			),
			execution.stepExecutions.sortedBy { it.startTime }.map { it.stepName },
		)
		val run = jdbcTemplate.queryForMap("SELECT * FROM settlement_runs WHERE settlement_date = ?", EMPTY_SETTLEMENT_DATE)
		assertEquals("COMPLETED", run["status"])
		assertEquals(0L, (run["collected_count"] as Number).toLong())
		assertEquals(0L, (run["collected_amount"] as Number).toLong())
		assertNotNull(run["collection_completed_at"])
		assertNotNull(run["confirmation_completed_at"])
		assertNotNull(run["completed_at"])
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets", Long::class.java))
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long::class.java))
	}

	@Test
	@DisplayName("한국 날짜 경계의 승인 결제만 스냅샷으로 수집한다")
	fun 한국_날짜_경계의_승인_결제만_스냅샷으로_수집한다() {
		val includedAtStart = insertPayment(approvedAt = Instant.parse("2026-09-14T15:00:00Z"), status = "APPROVED")
		insertPayment(approvedAt = Instant.parse("2026-09-15T15:00:00Z"), status = "APPROVED")
		insertPayment(approvedAt = Instant.parse("2026-09-15T00:00:00Z"), status = "FAILED")

		val execution = launch(SETTLEMENT_DATE, 300L)

		assertEquals(BatchStatus.COMPLETED, execution.status)
		val details = jdbcTemplate.queryForList("SELECT * FROM settlement_details ORDER BY payment_approved_at, payment_id")
		assertEquals(1, details.size)
		assertEquals(includedAtStart, (details.single()["payment_id"] as Number).toLong())
		assertEquals(1L, (details.single()["quantity"] as Number).toLong())
		assertEquals(10_000L, (details.single()["unit_price"] as Number).toLong())
		assertEquals(10_000L, (details.single()["gross_amount"] as Number).toLong())
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT collected_count FROM settlement_runs", Long::class.java))
		assertEquals(10_000L, jdbcTemplate.queryForObject("SELECT collected_amount FROM settlement_runs", Long::class.java))
		val sellerSettlement = jdbcTemplate.queryForMap("SELECT * FROM seller_settlements")
		assertEquals(1L, (sellerSettlement["detail_count"] as Number).toLong())
		assertEquals(10_000L, (sellerSettlement["gross_amount"] as Number).toLong())
		assertEquals(300L, (sellerSettlement["platform_fee_amount"] as Number).toLong())
		assertEquals(9_700L, (sellerSettlement["net_amount"] as Number).toLong())
		assertEquals("CREDITED", sellerSettlement["status"])
		assertNotNull(sellerSettlement["credited_at"])
		assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
		assertEquals(9_700L, jdbcTemplate.queryForObject("SELECT balance FROM wallets", Long::class.java))
		val ledgerEntry = jdbcTemplate.queryForMap("SELECT * FROM ledger_entries")
		assertEquals("CREDIT", ledgerEntry["direction"])
		assertEquals(9_700L, (ledgerEntry["amount"] as Number).toLong())
		assertEquals(9_700L, (ledgerEntry["balance_after"] as Number).toLong())
		assertEquals("SELLER_SETTLEMENT", ledgerEntry["source_type"])
		assertEquals((sellerSettlement["id"] as Number).toLong(), (ledgerEntry["source_id"] as Number).toLong())
	}

	@Test
	@DisplayName("chunk 실패 후 같은 JobInstance를 재시작하면 checkpoint 다음 항목부터 수집한다")
	fun chunk_실패_후_같은_JobInstance를_재시작하면_checkpoint_다음_항목부터_수집한다() {
		val validSaleId = insertSale(sellerId = 1L, mapRecipient = true)
		repeat(100) { index ->
			insertPayment(
				approvedAt = Instant.parse("2026-09-13T15:00:00Z").plusMillis(index.toLong()),
				saleId = validSaleId,
			)
		}
		val invalidSaleId = insertSale(sellerId = 2L, mapRecipient = false)
		insertPayment(
			approvedAt = Instant.parse("2026-09-13T15:00:01Z"),
			saleId = invalidSaleId,
		)

		val failed = launch(RESTART_SETTLEMENT_DATE, 350L)

		assertEquals(BatchStatus.FAILED, failed.status)
		assertEquals(100L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settlement_details", Long::class.java))
		assertEquals("COLLECTING", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
		val firstCollect = failed.stepExecutions.single { it.stepName == "collectSettlementDetailsStep" }
		assertEquals(100L, firstCollect.writeCount)
		assertTrue(firstCollect.commitCount >= 1)
		assertTrue(firstCollect.executionContext.containsKey("settlementPaymentReader.start.after"))

		insertSellerIdentity(sellerId = 2L)
		val restarted = launch(RESTART_SETTLEMENT_DATE, 350L)

		assertEquals(BatchStatus.COMPLETED, restarted.status)
		assertEquals(101L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settlement_details", Long::class.java))
		val restartedCollect = restarted.stepExecutions.single { it.stepName == "collectSettlementDetailsStep" }
		assertEquals(1L, restartedCollect.readCount)
		assertEquals(1L, restartedCollect.writeCount)
		assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_execution", Long::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_instance", Long::class.java))
	}

	@Test
	@DisplayName("재시작 수수료율이 최초 스냅샷과 다르면 실패한다")
	fun 재시작_수수료율이_최초_스냅샷과_다르면_실패한다() {
		insertPayment(
			approvedAt = Instant.parse("2026-09-12T15:00:00Z"),
			orderStatus = "PENDING_PAYMENT",
		)
		assertEquals(BatchStatus.FAILED, launch(FEE_MISMATCH_SETTLEMENT_DATE, 400L).status)

		val restarted = launch(FEE_MISMATCH_SETTLEMENT_DATE, 401L)

		assertEquals(BatchStatus.FAILED, restarted.status)
		assertEquals(400, jdbcTemplate.queryForObject("SELECT platform_fee_rate_bps FROM settlement_runs", Int::class.java))
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settlement_details", Long::class.java))
	}

	@Test
	@DisplayName("완료된 같은 정산일은 새로운 실행으로 다시 수집하지 않는다")
	fun 완료된_같은_정산일은_새로운_실행으로_다시_수집하지_않는다() {
		assertEquals(BatchStatus.COMPLETED, launch(COMPLETED_SETTLEMENT_DATE, 450L).status)

		assertFailsWith<JobInstanceAlreadyCompleteException> {
			launch(COMPLETED_SETTLEMENT_DATE, 999L)
		}
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settlement_runs", Long::class.java))
	}

	@Test
	@DisplayName("완료 처리는 같은 집계에 멱등하고 다른 집계에는 실패한다")
	fun 완료_처리는_같은_집계에_멱등하고_다른_집계에는_실패한다() {
		insertPayment(approvedAt = Instant.parse("2026-09-10T15:00:00Z"))
		val execution = launch(IDEMPOTENCY_SETTLEMENT_DATE, 500L)
		assertEquals(BatchStatus.COMPLETED, execution.status)
		val completedAt = jdbcTemplate.queryForObject(
			"SELECT collection_completed_at FROM settlement_runs",
			java.time.OffsetDateTime::class.java,
		)
		val tasklet = context.getBean(CompleteSettlementCollectionTasklet::class.java)

		executeCompleteTasklet(tasklet, execution)

		assertEquals(
			completedAt,
			jdbcTemplate.queryForObject("SELECT collection_completed_at FROM settlement_runs", java.time.OffsetDateTime::class.java),
		)
		jdbcTemplate.update("UPDATE settlement_details SET gross_amount = gross_amount + 1")
		assertFailsWith<SettlementRunStateException> {
			executeCompleteTasklet(tasklet, execution)
		}
	}

	@Test
	@DisplayName("확정 업무 반영 후 메타데이터 완료 전 장애가 나면 같은 JobInstance가 결과를 재검산한다")
	fun 확정_업무_반영_후_메타데이터_완료_전_장애가_나면_같은_JobInstance가_결과를_재검산한다() {
		insertPayment(approvedAt = Instant.parse("2026-09-07T15:00:00Z"))
		val firstExecution = launch(CONFIRMATION_RESTART_SETTLEMENT_DATE, 500L)
		assertEquals(BatchStatus.COMPLETED, firstExecution.status)
		val firstResult = jdbcTemplate.queryForMap("SELECT id, confirmed_at, created_at FROM seller_settlements")

		assertEquals(
			1,
			jdbcTemplate.update(
				"""
				UPDATE batch_step_execution
				SET status = 'FAILED', exit_code = 'FAILED', exit_message = '확정 메타데이터 반영 전 장애 재현'
				WHERE job_execution_id = ? AND step_name = 'confirmSellerSettlementsStep'
				""".trimIndent(),
				firstExecution.id,
			),
		)
		assertEquals(
			1,
			jdbcTemplate.update(
				"""
				UPDATE batch_job_execution
				SET status = 'FAILED', exit_code = 'FAILED', exit_message = '확정 메타데이터 반영 전 장애 재현'
				WHERE job_execution_id = ?
				""".trimIndent(),
				firstExecution.id,
			),
		)

		val restarted = launch(CONFIRMATION_RESTART_SETTLEMENT_DATE, 500L)

		assertEquals(BatchStatus.COMPLETED, restarted.status)
		assertEquals(
			listOf(
				"prepareSettlementRunStep",
				"confirmSellerSettlementsStep",
				"creditSellerWalletsStep",
				"completeSettlementRunStep",
			),
			restarted.stepExecutions.sortedBy { it.startTime }.map { it.stepName },
		)
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
		assertEquals(firstResult, jdbcTemplate.queryForMap("SELECT id, confirmed_at, created_at FROM seller_settlements"))
		assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_execution", Long::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_instance", Long::class.java))
	}

	@Test
	@DisplayName("확정 실패 후 같은 JobInstance를 재시작하면 수집 단계를 건너뛰고 확정한다")
	fun 확정_실패_후_같은_JobInstance를_재시작하면_수집_단계를_건너뛰고_확정한다() {
		insertPayment(approvedAt = Instant.parse("2026-09-06T15:00:00Z"))
		jdbcTemplate.execute(
			"""
			CREATE FUNCTION fail_seller_settlement_insert() RETURNS trigger AS ${'$'}${'$'}
			BEGIN
				RAISE EXCEPTION '확정 실패 재현';
			END;
			${'$'}${'$'} LANGUAGE plpgsql;
			CREATE TRIGGER fail_seller_settlement_insert_trigger
			BEFORE INSERT ON seller_settlements
			FOR EACH ROW EXECUTE FUNCTION fail_seller_settlement_insert();
			""".trimIndent(),
		)

		val failed = launch(CONFIRMATION_FAILURE_SETTLEMENT_DATE, 500L)

		assertEquals(BatchStatus.FAILED, failed.status)
		assertEquals("COLLECTED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settlement_details", Long::class.java))
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
		jdbcTemplate.execute("DROP TRIGGER fail_seller_settlement_insert_trigger ON seller_settlements")
		jdbcTemplate.execute("DROP FUNCTION fail_seller_settlement_insert()")

		val restarted = launch(CONFIRMATION_FAILURE_SETTLEMENT_DATE, 500L)

		assertEquals(BatchStatus.COMPLETED, restarted.status)
		assertEquals(
			listOf(
				"prepareSettlementRunStep",
				"confirmSellerSettlementsStep",
				"creditSellerWalletsStep",
				"completeSettlementRunStep",
			),
			restarted.stepExecutions.sortedBy { it.startTime }.map { it.stepName },
		)
		assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settlement_details", Long::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements", Long::class.java))
		assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_execution", Long::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch_job_instance", Long::class.java))
	}

	@Test
	@DisplayName("입금 chunk 중간 실패 후 재시작하면 이전 chunk를 유지하고 중복 입금하지 않는다")
	fun 입금_chunk_중간_실패_후_재시작하면_이전_chunk를_유지하고_중복_입금하지_않는다() {
		repeat(101) { index ->
			insertPayment(
				approvedAt = Instant.parse("2026-09-05T15:00:00Z").plusMillis(index.toLong()),
			)
		}
		jdbcTemplate.execute(
			"""
			CREATE FUNCTION fail_last_settlement_credit() RETURNS trigger AS ${'$'}${'$'}
			BEGIN
				IF NEW.source_id = 101 THEN RAISE EXCEPTION '마지막 입금 실패 재현'; END IF;
				RETURN NEW;
			END;
			${'$'}${'$'} LANGUAGE plpgsql;
			CREATE TRIGGER fail_last_settlement_credit_trigger
			BEFORE INSERT ON ledger_entries
			FOR EACH ROW EXECUTE FUNCTION fail_last_settlement_credit();
			""".trimIndent(),
		)

		val failed = launch(CREDIT_RESTART_SETTLEMENT_DATE, 0L)

		assertEquals(BatchStatus.FAILED, failed.status)
		assertEquals("CONFIRMED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
		assertEquals(100L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements WHERE status = 'CREDITED'", Long::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements WHERE status = 'CONFIRMED'", Long::class.java))
		assertEquals(100L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets", Long::class.java))
		assertEquals(100L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long::class.java))
		val failedCreditStep = failed.stepExecutions.single { it.stepName == "creditSellerWalletsStep" }
		assertEquals(100L, failedCreditStep.writeCount)
		assertTrue(failedCreditStep.executionContext.containsKey("sellerSettlementIdReader.start.after"))

		jdbcTemplate.execute("DROP TRIGGER fail_last_settlement_credit_trigger ON ledger_entries")
		jdbcTemplate.execute("DROP FUNCTION fail_last_settlement_credit()")
		val restarted = launch(CREDIT_RESTART_SETTLEMENT_DATE, 0L)

		assertEquals(BatchStatus.COMPLETED, restarted.status)
		assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
		assertEquals(101L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seller_settlements WHERE status = 'CREDITED'", Long::class.java))
		assertEquals(101L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets", Long::class.java))
		assertEquals(101L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long::class.java))
		val restartedCreditStep = restarted.stepExecutions.single { it.stepName == "creditSellerWalletsStep" }
		assertEquals(1L, restartedCreditStep.readCount)
		assertEquals(1L, restartedCreditStep.writeCount)
	}

	@Test
	@DisplayName("0원 정산은 지갑과 원장 없이 입금 완료한다")
	fun 금액이_0인_정산은_지갑과_원장_없이_입금_완료한다() {
		insertPayment(approvedAt = Instant.parse("2026-09-04T15:00:00Z"))

		val execution = launch(ZERO_CREDIT_SETTLEMENT_DATE, 10_000L)

		assertEquals(BatchStatus.COMPLETED, execution.status)
		val settlement = jdbcTemplate.queryForMap("SELECT * FROM seller_settlements")
		assertEquals(0L, (settlement["net_amount"] as Number).toLong())
		assertEquals("CREDITED", settlement["status"])
		assertNotNull(settlement["credited_at"])
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets", Long::class.java))
		assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long::class.java))
	}

	@Test
	@DisplayName("완료된 실행의 원장이 변조되면 재검산에 실패한다")
	fun 완료된_실행의_원장이_변조되면_재검산에_실패한다() {
		insertPayment(approvedAt = Instant.parse("2026-09-03T15:00:00Z"))
		val firstExecution = launch(COMPLETED_REVALIDATION_SETTLEMENT_DATE, 500L)
		assertEquals(BatchStatus.COMPLETED, firstExecution.status)
		val originalBalance = jdbcTemplate.queryForObject("SELECT balance FROM wallets", Long::class.java)
		jdbcTemplate.update("UPDATE ledger_entries SET amount = amount + 1")
		markStepAndJobFailed(firstExecution, "confirmSellerSettlementsStep")

		val restarted = launch(COMPLETED_REVALIDATION_SETTLEMENT_DATE, 500L)

		assertEquals(BatchStatus.FAILED, restarted.status)
		assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
		assertEquals(originalBalance, jdbcTemplate.queryForObject("SELECT balance FROM wallets", Long::class.java))
		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long::class.java))
		assertEquals(BatchStatus.FAILED, restarted.stepExecutions.single { it.stepName == "creditSellerWalletsStep" }.status)
	}

	@Test
	@DisplayName("입금 업무 반영 후 Step 완료 전 장애를 재시작하면 기존 결과를 재검산한다")
	fun 입금_업무_반영_후_Step_완료_전_장애를_재시작하면_기존_결과를_재검산한다() {
		insertPayment(approvedAt = Instant.parse("2026-09-02T15:00:00Z"))
		val firstExecution = launch(CREDIT_METADATA_RESTART_SETTLEMENT_DATE, 500L)
		assertEquals(BatchStatus.COMPLETED, firstExecution.status)
		val originalWallet = jdbcTemplate.queryForMap("SELECT id, user_id, balance, created_at, updated_at FROM wallets")
		val originalLedger = jdbcTemplate.queryForMap("SELECT * FROM ledger_entries")
		markStepAndJobFailed(firstExecution, "creditSellerWalletsStep")
		jdbcTemplate.update(
			"""
			UPDATE batch_step_execution_context target
			SET short_context = source.short_context, serialized_context = source.serialized_context
			FROM batch_step_execution target_step, batch_step_execution source_step,
			     batch_step_execution_context source
			WHERE target.step_execution_id = target_step.step_execution_id
			  AND target_step.job_execution_id = ?
			  AND target_step.step_name = 'creditSellerWalletsStep'
			  AND source.step_execution_id = source_step.step_execution_id
			  AND source_step.job_execution_id = ?
			  AND source_step.step_name = 'confirmSellerSettlementsStep'
			""".trimIndent(),
			firstExecution.id,
			firstExecution.id,
		)

		val restarted = launch(CREDIT_METADATA_RESTART_SETTLEMENT_DATE, 500L)

		assertEquals(BatchStatus.COMPLETED, restarted.status)
		val creditStep = restarted.stepExecutions.single { it.stepName == "creditSellerWalletsStep" }
		assertEquals(1L, creditStep.readCount)
		assertEquals(1L, creditStep.writeCount)
		assertEquals(originalWallet, jdbcTemplate.queryForMap("SELECT id, user_id, balance, created_at, updated_at FROM wallets"))
		assertEquals(originalLedger, jdbcTemplate.queryForMap("SELECT * FROM ledger_entries"))
		assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT status FROM settlement_runs", String::class.java))
	}

	private fun launch(settlementDate: LocalDate, platformFeeRateBps: Long) =
		jobOperator.start(
			job,
			JobParametersBuilder()
				.addString("settlementDate", settlementDate.toString(), true)
				.addLong("platformFeeRateBps", platformFeeRateBps, false)
				.toJobParameters(),
		)

	private fun markStepAndJobFailed(execution: org.springframework.batch.core.job.JobExecution, stepName: String) {
		jdbcTemplate.update(
			"UPDATE batch_step_execution SET status = 'FAILED', exit_code = 'FAILED' WHERE job_execution_id = ? AND step_name = ?",
			execution.id,
			stepName,
		)
		jdbcTemplate.update(
			"UPDATE batch_job_execution SET status = 'FAILED', exit_code = 'FAILED' WHERE job_execution_id = ?",
			execution.id,
		)
	}

	private fun String.withCurrentSchema(schema: String): String =
		this + if (contains('?')) "&currentSchema=$schema" else "?currentSchema=$schema"

	private fun executeCompleteTasklet(
		tasklet: CompleteSettlementCollectionTasklet,
		execution: org.springframework.batch.core.job.JobExecution,
	) {
		val stepExecution = execution.stepExecutions.single { it.stepName == "completeSettlementCollectionStep" }
		tasklet.execute(stepExecution.createStepContribution(), ChunkContext(StepContext(stepExecution)))
	}

	private fun insertPayment(
		approvedAt: Instant,
		status: String = "APPROVED",
		orderStatus: String = "PAID",
		saleId: Long = insertSale(sellerId = nextSellerId++, mapRecipient = true),
	): Long {
		val orderId = jdbcTemplate.queryForObject(
			"""
			INSERT INTO orders (
				sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price,
				status, recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
			) VALUES (?, ?, ?, ?, 1, '상품', 10000, 10000, ?, '수령인', '010-0000-0000', '00000', '주소', '상세', ?, ?)
			RETURNING id
			""".trimIndent(),
			Long::class.java,
			saleId,
			nextBuyerId++,
			UUID.randomUUID(),
			"order-${UUID.randomUUID()}",
			orderStatus,
			Instant.parse("2026-09-14T00:00:00Z").atOffset(ZoneOffset.UTC),
			Instant.parse("2026-09-14T00:10:00Z").atOffset(ZoneOffset.UTC),
		)!!
		return jdbcTemplate.queryForObject(
			"""
			INSERT INTO payments (
				order_id, payment_key, toss_idempotency_key, status, requested_amount, created_at, approved_at
			) VALUES (?, ?, ?, ?, 10000, ?, ?)
			RETURNING id
			""".trimIndent(),
			Long::class.java,
			orderId,
			"payment-$orderId",
			UUID.randomUUID().toString(),
			status,
			Instant.parse("2026-09-14T00:00:00Z").atOffset(ZoneOffset.UTC),
			approvedAt.atOffset(ZoneOffset.UTC),
		)!!
	}

	private fun insertSale(sellerId: Long, mapRecipient: Boolean): Long {
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (?, ?, 'DRAFT', ?) RETURNING id",
			Long::class.java,
			sellerId,
			"상품-$sellerId",
			Instant.parse("2026-09-01T00:00:00Z").atOffset(ZoneOffset.UTC),
		)!!
		jdbcTemplate.update(
			"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 1000, 1) ON CONFLICT (sale_date) DO NOTHING",
			LocalDate.parse("2026-09-15"),
		)
		val saleId = jdbcTemplate.queryForObject(
			"""
			INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
			VALUES (?, ?, ?, 10000, 1000, ?) RETURNING id
			""".trimIndent(),
			Long::class.java,
			productId,
			sellerId,
			LocalDate.parse("2026-09-15"),
			Instant.parse("2026-09-01T00:00:00Z").atOffset(ZoneOffset.UTC),
		)!!
		if (mapRecipient) {
			insertSellerIdentity(sellerId)
		}
		return saleId
	}

	private fun insertSellerIdentity(sellerId: Long) {
		val userId = jdbcTemplate.queryForObject(
			"""
			INSERT INTO users (provider, provider_subject, email, created_at)
			VALUES ('GOOGLE', ?, ?, ?) RETURNING id
			""".trimIndent(),
			Long::class.java,
			"seller-$sellerId",
			"seller-$sellerId@example.com",
			Instant.parse("2026-09-01T00:00:00Z").atOffset(ZoneOffset.UTC),
		)!!
		jdbcTemplate.update(
			"INSERT INTO seller_principal_identities (user_id, seller_id) VALUES (?, ?)",
			userId,
			sellerId,
		)
	}

	private companion object {
		const val SCHEMA = "daily_seller_settlement_job"
		val SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 15)
		val EMPTY_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 9)
		val RESTART_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 14)
		val FEE_MISMATCH_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 13)
		val COMPLETED_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 12)
		val IDEMPOTENCY_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 11)
		val CONFIRMATION_RESTART_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 8)
		val CONFIRMATION_FAILURE_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 7)
		val CREDIT_RESTART_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 6)
		val ZERO_CREDIT_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 5)
		val COMPLETED_REVALIDATION_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 4)
		val CREDIT_METADATA_RESTART_SETTLEMENT_DATE: LocalDate = LocalDate.of(2026, 9, 3)
		var nextSellerId = 100L
		var nextBuyerId = 1L

		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
