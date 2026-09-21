package io.github.sehako.japda.batch.performance.validation

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import java.util.Locale
import org.springframework.jdbc.core.JdbcTemplate

data class ValidationReport(
	val success: Boolean,
	val values: Map<String, String>,
	val failures: List<String>,
) {
	fun merge(other: ValidationReport): ValidationReport = ValidationReport(
		success = success && other.success,
		values = values + other.values,
		failures = failures + other.failures,
	)
}

class BatchCounterValidator(
	private val chunkSize: Int,
) {
	init {
		require(chunkSize > 0) { "chunk size는 양수여야 합니다." }
	}

	fun validate(
		steps: List<StepMeasurement>,
		expectedOrderCount: Long,
		expectedSellerCount: Long,
	): ValidationReport {
		val failures = mutableListOf<String>()
		REQUIRED_STEPS.forEach { requiredStep ->
			if (steps.none { it.name == requiredStep }) failures += "필수 Step 실행 결과가 없습니다: $requiredStep"
		}
		steps.filter { it.status != ExecutionStatus.COMPLETED }
			.forEach { failures += "Step 상태가 COMPLETED가 아닙니다: ${it.name}=${it.status}" }
		steps.filter { it.skipCount != 0L }
			.forEach { failures += "Step skip count가 0이 아닙니다: ${it.name}=${it.skipCount}" }
		steps.filter { it.rollbackCount != 0L }
			.forEach { failures += "Step rollback count가 0이 아닙니다: ${it.name}=${it.rollbackCount}" }

		val values = linkedMapOf(
			"batch.chunk.size" to chunkSize.toString(),
			"batch.expected.order.count" to expectedOrderCount.toString(),
			"batch.expected.seller.count" to expectedSellerCount.toString(),
		)
		validateWorkerSteps(steps, COLLECTION_WORKER_PREFIX, "collection", expectedOrderCount, failures, values)
		validateWorkerSteps(steps, CREDIT_WORKER_PREFIX, "credit", expectedSellerCount, failures, values)
		return ValidationReport(
			success = failures.isEmpty(),
			values = values,
			failures = failures,
		)
	}

	private fun validateWorkerSteps(
		steps: List<StepMeasurement>,
		prefix: String,
		phase: String,
		expectedCount: Long,
		failures: MutableList<String>,
		values: MutableMap<String, String>,
	) {
		val workers = steps.filter { it.name.startsWith("$prefix:") }
		if (expectedCount > 0 && workers.isEmpty()) {
			failures += "partition worker Step 실행 결과가 없습니다: $prefix"
		}
		val readCount = workers.sumOf { it.readCount }
		val writeCount = workers.sumOf { it.writeCount }
		val commitCount = workers.sumOf { it.commitCount }
		values["batch.$phase.worker.read.count"] = readCount.toString()
		values["batch.$phase.worker.write.count"] = writeCount.toString()
		values["batch.$phase.worker.commit.count"] = commitCount.toString()
		if (readCount != expectedCount) failures += "$prefix read count 불일치: expected=$expectedCount, actual=$readCount"
		if (writeCount != expectedCount) failures += "$prefix write count 불일치: expected=$expectedCount, actual=$writeCount"
		workers.filter { it.filterCount != 0L }
			.forEach { failures += "${it.name} filter count가 0이 아닙니다: ${it.filterCount}" }
		val expectedCommitCount = workers.sumOf { (it.readCount + chunkSize - 1L) / chunkSize }
		if (commitCount != expectedCommitCount) {
			failures += "$prefix commit count 불일치: expected=$expectedCommitCount, actual=$commitCount"
		}
	}

	private companion object {
		const val COLLECTION_WORKER_PREFIX = "collectSettlementDetailsWorkerStep"
		const val CREDIT_WORKER_PREFIX = "creditSellerWalletsWorkerStep"
		val REQUIRED_STEPS = setOf(
			"prepareSettlementRunStep",
			"prepareCollectionPartitionPlanStep",
			"collectSettlementDetailsManagerStep",
			"completeSettlementCollectionStep",
			"confirmSellerSettlementsStep",
			"prepareCreditPartitionPlanStep",
			"creditSellerWalletsManagerStep",
			"completeSettlementRunStep",
		)
	}
}

class PartitionSkewValidator {
	fun validate(steps: List<StepMeasurement>, expectedEntryCount: Long = Long.MAX_VALUE): ValidationReport {
		val values = linkedMapOf<String, String>()
		val failures = mutableListOf<String>()
		val reportOnly = expectedEntryCount < MINIMUM_SKEW_EVALUATION_COUNT
		validatePhase(steps, COLLECTION_PHASE, values, failures, reportOnly)
		validatePhase(steps, CREDIT_PHASE, values, failures, reportOnly)
		return ValidationReport(failures.isEmpty(), values, failures)
	}

	private fun validatePhase(
		steps: List<StepMeasurement>,
		phase: Phase,
		values: MutableMap<String, String>,
		failures: MutableList<String>,
		reportOnly: Boolean,
	) {
		val manager = steps.singleOrNull { it.name == phase.managerStep } ?: return
		val longestWorker = steps.filter { it.name.startsWith("${phase.workerPrefix}:") }
			.maxByOrNull { it.durationMillis } ?: return
		val ratio = if (manager.durationMillis > 0) {
			longestWorker.durationMillis.toDouble() / manager.durationMillis
		} else {
			Double.POSITIVE_INFINITY
		}
		val partition = longestWorker.name.substringAfter(':')
		values["partition.${phase.name}.longest.name"] = partition
		values["partition.${phase.name}.longest.duration.millis"] = longestWorker.durationMillis.toString()
		values["partition.${phase.name}.phase.duration.millis"] = manager.durationMillis.toString()
		values["partition.${phase.name}.longest.ratio"] = formatRatio(ratio)
		if (!reportOnly && ratio > MAX_LONGEST_PARTITION_RATIO) {
			failures += "${phase.name} 파티션 처리 편향이 25.0%를 초과했습니다: partition=$partition, ratio=${formatPercent(ratio)}%"
		}
	}

	private fun formatRatio(value: Double) = String.format(Locale.ROOT, "%.3f", value)

	private fun formatPercent(value: Double) = String.format(Locale.ROOT, "%.1f", value * 100)

	private data class Phase(val name: String, val managerStep: String, val workerPrefix: String)

	private companion object {
		const val MAX_LONGEST_PARTITION_RATIO = 0.25
		const val MINIMUM_SKEW_EVALUATION_COUNT = 1_000_000L
		val COLLECTION_PHASE = Phase("collection", "collectSettlementDetailsManagerStep", "collectSettlementDetailsWorkerStep")
		val CREDIT_PHASE = Phase("credit", "creditSellerWalletsManagerStep", "creditSellerWalletsWorkerStep")
	}
}

object PerformanceHarnessConfigurationValidator {
	fun validate(workerCount: Int, maximumPoolSize: Int) {
		val requiredPoolSize = workerCount + CONNECTION_POOL_HEADROOM
		check(maximumPoolSize >= requiredPoolSize) {
			"Hikari maximumPoolSize는 workerCount + 4 이상이어야 합니다: " +
				"workerCount=$workerCount, required=$requiredPoolSize, actual=$maximumPoolSize"
		}
	}

	private const val CONNECTION_POOL_HEADROOM = 4
}

data class ExpectedSettlementValues(
	val detailCount: Long,
	val sellerSettlementCount: Long,
	val grossAmount: Long,
	val platformFeeAmount: Long,
	val netAmount: Long,
)

data class ActualSettlementValues(
	val detailCount: Long,
	val sellerSettlementCount: Long,
	val detailGrossAmount: Long,
	val sellerGrossAmount: Long,
	val platformFeeAmount: Long,
	val netAmount: Long,
	val creditedSellerCount: Long,
	val completedRunCount: Long,
	val runCollectedCount: Long,
	val runCollectedAmount: Long,
	val positiveNetSellerCount: Long,
	val matchingLedgerCount: Long,
	val ledgerCreditAmount: Long,
	val walletBalanceAmount: Long,
	val walletLastBalanceMismatchCount: Long,
)

object SettlementValidationEvaluator {
	fun validate(expected: ExpectedSettlementValues, actual: ActualSettlementValues): ValidationReport {
		val failures = mutableListOf<String>()
		checkEqual("정산 원천 건수", expected.detailCount, actual.detailCount, failures)
		checkEqual("판매자별 정산 건수", expected.sellerSettlementCount, actual.sellerSettlementCount, failures)
		checkEqual("정산 원천 gross 합계", expected.grossAmount, actual.detailGrossAmount, failures)
		checkEqual("판매자별 정산 gross 합계", expected.grossAmount, actual.sellerGrossAmount, failures)
		checkEqual("수수료 합계", expected.platformFeeAmount, actual.platformFeeAmount, failures)
		checkEqual("net 합계", expected.netAmount, actual.netAmount, failures)
		checkEqual("입금 완료 판매자 수", expected.sellerSettlementCount, actual.creditedSellerCount, failures)
		checkEqual("완료 정산 run 수", 1, actual.completedRunCount, failures)
		checkEqual("run 수집 건수", expected.detailCount, actual.runCollectedCount, failures)
		checkEqual("run 수집 금액", expected.grossAmount, actual.runCollectedAmount, failures)
		checkEqual("원장 일치 건수", actual.positiveNetSellerCount, actual.matchingLedgerCount, failures)
		checkEqual("원장 입금액 합계", expected.netAmount, actual.ledgerCreditAmount, failures)
		checkEqual("지갑 잔액 합계", expected.netAmount, actual.walletBalanceAmount, failures)
		checkEqual("지갑과 마지막 원장 잔액 불일치 건수", 0, actual.walletLastBalanceMismatchCount, failures)
		val values = linkedMapOf(
			"expected.entry.count" to expected.detailCount.toString(),
			"actual.entry.count" to actual.detailCount.toString(),
			"expected.seller.settlement.count" to expected.sellerSettlementCount.toString(),
			"actual.seller.settlement.count" to actual.sellerSettlementCount.toString(),
			"expected.gross.amount" to expected.grossAmount.toString(),
			"actual.entry.gross.amount" to actual.detailGrossAmount.toString(),
			"actual.seller.gross.amount" to actual.sellerGrossAmount.toString(),
			"expected.platform.fee.amount" to expected.platformFeeAmount.toString(),
			"actual.platform.fee.amount" to actual.platformFeeAmount.toString(),
			"expected.net.amount" to expected.netAmount.toString(),
			"actual.net.amount" to actual.netAmount.toString(),
			"actual.credited.seller.count" to actual.creditedSellerCount.toString(),
			"actual.completed.run.count" to actual.completedRunCount.toString(),
			"actual.run.collected.count" to actual.runCollectedCount.toString(),
			"actual.run.collected.amount" to actual.runCollectedAmount.toString(),
			"actual.matching.ledger.count" to actual.matchingLedgerCount.toString(),
			"actual.ledger.credit.amount" to actual.ledgerCreditAmount.toString(),
			"actual.wallet.balance.amount" to actual.walletBalanceAmount.toString(),
			"actual.wallet.last.balance.mismatch.count" to actual.walletLastBalanceMismatchCount.toString(),
		)
		return ValidationReport(failures.isEmpty(), values, failures)
	}

	private fun checkEqual(label: String, expected: Long, actual: Long, failures: MutableList<String>) {
		if (expected != actual) failures += "$label 불일치: expected=$expected, actual=$actual"
	}
}

class SettlementResultValidator(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun validate(settlementRunId: Long, expected: ExpectedSettlementValues): ValidationReport =
		SettlementValidationEvaluator.validate(expected, queryActual(settlementRunId))

	private fun queryActual(settlementRunId: Long): ActualSettlementValues {
		val aggregate = jdbcTemplate.queryForMap(
			"""
			SELECT
			  (SELECT COUNT(*) FROM settlement_entries WHERE settlement_date =
			     (SELECT settlement_date FROM settlement_runs WHERE id = ?)) AS detail_count,
			  (SELECT COALESCE(SUM(gross_amount), 0) FROM settlement_entries WHERE settlement_date =
			     (SELECT settlement_date FROM settlement_runs WHERE id = ?)) AS detail_gross,
			  (SELECT COUNT(*) FROM seller_settlements WHERE settlement_run_id = ?) AS seller_count,
			  (SELECT COALESCE(SUM(gross_amount), 0) FROM seller_settlements WHERE settlement_run_id = ?) AS seller_gross,
			  (SELECT COALESCE(SUM(platform_fee_amount), 0) FROM seller_settlements WHERE settlement_run_id = ?) AS fee_amount,
			  (SELECT COALESCE(SUM(net_amount), 0) FROM seller_settlements WHERE settlement_run_id = ?) AS net_amount,
			  (SELECT COUNT(*) FROM seller_settlements WHERE settlement_run_id = ? AND status = 'CREDITED' AND credited_at IS NOT NULL) AS credited_count,
			  (SELECT COUNT(*) FROM settlement_runs WHERE id = ? AND status = 'COMPLETED' AND completed_at IS NOT NULL
			     AND collection_completed_at IS NOT NULL AND confirmation_completed_at IS NOT NULL) AS completed_run_count,
			  (SELECT collected_count FROM settlement_runs WHERE id = ?) AS run_collected_count,
			  (SELECT collected_amount FROM settlement_runs WHERE id = ?) AS run_collected_amount,
			  (SELECT COUNT(*) FROM seller_settlements WHERE settlement_run_id = ? AND net_amount > 0) AS positive_net_count,
			  (SELECT COUNT(*) FROM seller_settlements ss JOIN ledger_entries le
			     ON le.source_type = 'SELLER_SETTLEMENT' AND le.source_id = ss.id
			     JOIN wallets w ON w.id = le.wallet_id
			     WHERE ss.settlement_run_id = ? AND ss.net_amount > 0 AND w.user_id = ss.recipient_user_id
			       AND le.direction = 'CREDIT' AND le.amount = ss.net_amount) AS matching_ledger_count,
			  (SELECT COALESCE(SUM(le.amount), 0) FROM seller_settlements ss JOIN ledger_entries le
			     ON le.source_type = 'SELLER_SETTLEMENT' AND le.source_id = ss.id
			     WHERE ss.settlement_run_id = ?) AS ledger_amount,
			  (SELECT COALESCE(SUM(w.balance), 0) FROM wallets w
			     WHERE w.user_id IN (SELECT recipient_user_id FROM seller_settlements WHERE settlement_run_id = ?)) AS wallet_amount,
			  (SELECT COUNT(*) FROM wallets w
			     WHERE w.user_id IN (SELECT recipient_user_id FROM seller_settlements WHERE settlement_run_id = ?)
			       AND NOT EXISTS (
			         SELECT 1 FROM ledger_entries le
			         WHERE le.wallet_id = w.id
			           AND le.id = (SELECT MAX(last_le.id) FROM ledger_entries last_le WHERE last_le.wallet_id = w.id)
			           AND le.balance_after = w.balance
			       )) AS wallet_mismatch_count
			""".trimIndent(),
			*Array(15) { settlementRunId },
		)
		fun number(name: String) = (aggregate[name] as? Number)?.toLong() ?: 0L
		return ActualSettlementValues(
			detailCount = number("detail_count"),
			sellerSettlementCount = number("seller_count"),
			detailGrossAmount = number("detail_gross"),
			sellerGrossAmount = number("seller_gross"),
			platformFeeAmount = number("fee_amount"),
			netAmount = number("net_amount"),
			creditedSellerCount = number("credited_count"),
			completedRunCount = number("completed_run_count"),
			runCollectedCount = number("run_collected_count"),
			runCollectedAmount = number("run_collected_amount"),
			positiveNetSellerCount = number("positive_net_count"),
			matchingLedgerCount = number("matching_ledger_count"),
			ledgerCreditAmount = number("ledger_amount"),
			walletBalanceAmount = number("wallet_amount"),
			walletLastBalanceMismatchCount = number("wallet_mismatch_count"),
		)
	}
}
