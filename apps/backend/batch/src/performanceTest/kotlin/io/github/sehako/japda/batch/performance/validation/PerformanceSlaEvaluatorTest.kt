package io.github.sehako.japda.batch.performance.validation

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import io.github.sehako.japda.batch.performance.scenario.ApprovalTimeDistribution
import io.github.sehako.japda.batch.performance.scenario.DatasetScenario
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import io.github.sehako.japda.batch.performance.scenario.SettlementJobScenario
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

@DisplayName("대용량 정산 성능 SLA 판정")
class PerformanceSlaEvaluatorTest {
	@Test
	@DisplayName("1000만 FIXED 기준 시나리오는 전체와 단계별 상한을 모두 판정한다")
	fun 천만_FIXED_기준_시나리오는_전체와_단계별_상한을_모두_판정한다() {
		val result = PerformanceSlaEvaluator().evaluate(
			scenario(orderCount = 10_000_000, sellerCount = 100_000, distribution = ApprovalTimeDistribution.FIXED),
			Duration.ofMinutes(61).toMillis(),
			listOf(step("collectSettlementDetailsStep", 50), step("confirmSellerSettlementsStep", 5)),
		)

		assertFalse(result.success)
		assertTrue(result.failures.any { it.contains("전체 Job") })
		assertTrue(result.values["sla.profile"] == "TEN_MILLION_FIXED")
	}

	@Test
	@DisplayName("100만 기준 시나리오는 4분 상한을 넘으면 실패한다")
	fun 백만_기준_시나리오는_사분_상한을_넘으면_실패한다() {
		val result = PerformanceSlaEvaluator().evaluate(
			scenario(orderCount = 1_000_000, sellerCount = 10_000, distribution = ApprovalTimeDistribution.FIXED),
			Duration.ofMinutes(4).plusMillis(1).toMillis(),
			emptyList(),
		)

		assertFalse(result.success)
		assertTrue(result.failures.single().contains("100만"))
		assertTrue(result.values["sla.profile"] == "ONE_MILLION")
	}

	@Test
	@DisplayName("1000만 FIXED 기준 시나리오는 65분 timeout 미만을 공식 SLA 측정으로 인정하지 않는다")
	fun 천만_FIXED_기준_시나리오는_육십오분_timeout_미만을_공식_SLA_측정으로_인정하지_않는다() {
		val result = PerformanceSlaEvaluator().evaluate(
			scenario(orderCount = 10_000_000, sellerCount = 100_000, distribution = ApprovalTimeDistribution.FIXED, timeout = Duration.ofMinutes(64)),
			Duration.ofMinutes(59).toMillis(),
			listOf(step("collectSettlementDetailsStep", 49), step("confirmSellerSettlementsStep", 4)),
		)

		assertFalse(result.success)
		assertTrue(result.failures.any { it.contains("65분") })
	}

	@Test
	@DisplayName("1000만 FIXED 기준 시나리오는 입금과 완료 단계 합계가 5분을 넘으면 실패한다")
	fun 천만_FIXED_기준_시나리오는_입금과_완료_단계_합계가_오분을_넘으면_실패한다() {
		val result = PerformanceSlaEvaluator().evaluate(
			scenario(orderCount = 10_000_000, sellerCount = 100_000, distribution = ApprovalTimeDistribution.FIXED),
			Duration.ofMinutes(59).toMillis(),
			listOf(
				step("collectSettlementDetailsStep", 49),
				step("confirmSellerSettlementsStep", 4),
				step("creditSellerWalletsStep", 3),
				step("completeSettlementRunStep", 3),
			),
		)

		assertFalse(result.success)
		assertTrue(result.failures.any { it.contains("지갑 입금") })
	}

	private fun scenario(
		orderCount: Int,
		sellerCount: Int,
		distribution: ApprovalTimeDistribution,
		timeout: Duration = Duration.ofMinutes(65),
	) = PerformanceScenario(
		dataset = DatasetScenario(sellerCount, orderCount, 1_000, 1, 10_000, distribution),
		job = SettlementJobScenario(LocalDate.of(2026, 9, 15), 1_000, timeout),
		warmupIterations = 0,
		measurementIterations = 1,
		resourceSamplingInterval = Duration.ofMillis(100),
	)

	private fun step(name: String, minutes: Long) = StepMeasurement(
		name, ExecutionStatus.COMPLETED, Duration.ofMinutes(minutes).toMillis(), 0, 0, 0, 0, 0, 0,
	)
}
