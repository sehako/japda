package io.github.sehako.japda.batch.performance.validation

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
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

		validateChunkStep(steps, COLLECT_STEP, expectedOrderCount, failures)
		validateChunkStep(steps, CREDIT_STEP, expectedSellerCount, failures)
		return ValidationReport(
			success = failures.isEmpty(),
			values = linkedMapOf(
				"batch.chunk.size" to chunkSize.toString(),
				"batch.expected.order.count" to expectedOrderCount.toString(),
				"batch.expected.seller.count" to expectedSellerCount.toString(),
			),
			failures = failures,
		)
	}

	private fun validateChunkStep(
		steps: List<StepMeasurement>,
		name: String,
		expectedCount: Long,
		failures: MutableList<String>,
	) {
		val step = steps.singleOrNull { it.name == name }
		if (step == null) {
			return
		}
		if (step.readCount != expectedCount) failures += "$name read count 불일치: expected=$expectedCount, actual=${step.readCount}"
		if (step.writeCount != expectedCount) failures += "$name write count 불일치: expected=$expectedCount, actual=${step.writeCount}"
		if (step.filterCount != 0L) failures += "$name filter count가 0이 아닙니다: ${step.filterCount}"
		val expectedCommitCount = (expectedCount + chunkSize - 1L) / chunkSize
		if (step.commitCount != expectedCommitCount) {
			failures += "$name commit count 불일치: expected=$expectedCommitCount, actual=${step.commitCount}"
		}
	}

	private companion object {
		const val COLLECT_STEP = "collectSettlementDetailsStep"
		const val CREDIT_STEP = "creditSellerWalletsStep"
		val REQUIRED_STEPS = setOf(
			"prepareSettlementRunStep",
			COLLECT_STEP,
			"completeSettlementCollectionStep",
			"confirmSellerSettlementsStep",
			CREDIT_STEP,
			"completeSettlementRunStep",
		)
	}
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
		checkEqual("정산 상세 건수", expected.detailCount, actual.detailCount, failures)
		checkEqual("판매자별 정산 건수", expected.sellerSettlementCount, actual.sellerSettlementCount, failures)
		checkEqual("정산 상세 gross 합계", expected.grossAmount, actual.detailGrossAmount, failures)
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
			"expected.detail.count" to expected.detailCount.toString(),
			"actual.detail.count" to actual.detailCount.toString(),
			"expected.seller.settlement.count" to expected.sellerSettlementCount.toString(),
			"actual.seller.settlement.count" to actual.sellerSettlementCount.toString(),
			"expected.gross.amount" to expected.grossAmount.toString(),
			"actual.detail.gross.amount" to actual.detailGrossAmount.toString(),
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
			  (SELECT COUNT(*) FROM settlement_details WHERE settlement_run_id = ?) AS detail_count,
			  (SELECT COALESCE(SUM(gross_amount), 0) FROM settlement_details WHERE settlement_run_id = ?) AS detail_gross,
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
