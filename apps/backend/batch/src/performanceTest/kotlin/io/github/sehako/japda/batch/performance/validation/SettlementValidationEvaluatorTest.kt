package io.github.sehako.japda.batch.performance.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("정산 업무 결과 검산기")
class SettlementValidationEvaluatorTest {
	@Test
	@DisplayName("정산 원천이 일치하고 중복 상세가 없어도 검산에 성공한다")
	fun 정산_원천_일치_중복_상세_없음_검산_성공() {
		val expected = ExpectedSettlementValues(100, 10, 1_000_000, 100_000, 900_000)
		val actual = ActualSettlementValues(
			100, 10, 1_000_000, 1_000_000, 100_000, 900_000, 10, 1, 100, 1_000_000,
			10, 10, 900_000, 900_000, 0,
		)

		val report = SettlementValidationEvaluator.validate(expected, actual)

		assertThat(report.success).isTrue()
		assertThat(report.values).containsEntry("actual.entry.count", "100")
	}

	@Test
	fun 건수_금액_상태_지갑_원장_불변식이_모두_맞으면_성공한다() {
		val expected = ExpectedSettlementValues(3, 2, 303, 9, 294)
		val actual = ActualSettlementValues(
			detailCount = 3,
			sellerSettlementCount = 2,
			detailGrossAmount = 303,
			sellerGrossAmount = 303,
			platformFeeAmount = 9,
			netAmount = 294,
			creditedSellerCount = 2,
			completedRunCount = 1,
			runCollectedCount = 3,
			runCollectedAmount = 303,
			positiveNetSellerCount = 2,
			matchingLedgerCount = 2,
			ledgerCreditAmount = 294,
			walletBalanceAmount = 294,
			walletLastBalanceMismatchCount = 0,
		)

		val report = SettlementValidationEvaluator.validate(expected, actual)

		assertThat(report.success).isTrue()
		assertThat(report.failures).isEmpty()
		assertThat(report.values).containsEntry("actual.net.amount", "294")
	}

	@Test
	fun 원장과_지갑_불변식이_어긋나면_모든_실패를_보고한다() {
		val expected = ExpectedSettlementValues(1, 1, 100, 10, 90)
		val actual = ActualSettlementValues(
			detailCount = 1,
			sellerSettlementCount = 1,
			detailGrossAmount = 100,
			sellerGrossAmount = 100,
			platformFeeAmount = 10,
			netAmount = 90,
			creditedSellerCount = 1,
			completedRunCount = 1,
			runCollectedCount = 1,
			runCollectedAmount = 100,
			positiveNetSellerCount = 1,
			matchingLedgerCount = 0,
			ledgerCreditAmount = 80,
			walletBalanceAmount = 70,
			walletLastBalanceMismatchCount = 1,
		)

		val report = SettlementValidationEvaluator.validate(expected, actual)

		assertThat(report.success).isFalse()
		assertThat(report.failures).hasSize(4)
		assertThat(report.failures).anyMatch { it.contains("원장") }
		assertThat(report.failures).anyMatch { it.contains("마지막 원장") }
	}

	@Test
	fun settlement_run_최종_집계가_예상과_다르면_실패한다() {
		val report = SettlementValidationEvaluator.validate(
			ExpectedSettlementValues(3, 2, 303, 9, 294),
			ActualSettlementValues(3, 2, 303, 303, 9, 294, 2, 1, 2, 302, 2, 2, 294, 294, 0),
		)

		assertThat(report.failures).anyMatch { it.contains("run 수집 건수") }
		assertThat(report.failures).anyMatch { it.contains("run 수집 금액") }
	}
}
