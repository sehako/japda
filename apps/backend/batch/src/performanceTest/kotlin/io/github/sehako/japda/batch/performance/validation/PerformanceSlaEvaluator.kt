package io.github.sehako.japda.batch.performance.validation

import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import io.github.sehako.japda.batch.performance.scenario.ApprovalTimeDistribution
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import java.time.Duration

class PerformanceSlaEvaluator {
	fun evaluate(
		scenario: PerformanceScenario,
		jobDurationMillis: Long,
		steps: List<StepMeasurement>,
	): ValidationReport {
		val failures = mutableListOf<String>()
		val values = linkedMapOf<String, String>()
		if (scenario.dataset.orderCount == ONE_MILLION && jobDurationMillis > ONE_MILLION_JOB_LIMIT.toMillis()) {
			failures += "100만 건 기준 시나리오의 전체 Job 시간이 4분을 초과했습니다: ${jobDurationMillis}ms"
		}
		if (scenario.dataset.orderCount == ONE_MILLION) {
			values["sla.profile"] = "ONE_MILLION"
			values["sla.job.limit.millis"] = ONE_MILLION_JOB_LIMIT.toMillis().toString()
		}
		if (isTenMillionFixedBaseline(scenario)) {
			values["sla.profile"] = "TEN_MILLION_FIXED"
			values["sla.job.limit.millis"] = TEN_MILLION_JOB_LIMIT.toMillis().toString()
			values["sla.timeout.required.millis"] = OFFICIAL_TIMEOUT.toMillis().toString()
			if (scenario.job.timeout < OFFICIAL_TIMEOUT) {
				failures += "1000만 건 기준 시나리오의 Job timeout은 65분 이상이어야 합니다: ${scenario.job.timeout}"
			}
			if (jobDurationMillis > TEN_MILLION_JOB_LIMIT.toMillis()) {
				failures += "1000만 건 기준 시나리오의 전체 Job 시간이 60분을 초과했습니다: ${jobDurationMillis}ms"
			}
			assertStepLimit(steps, COLLECT_STEP, COLLECT_LIMIT, failures)
			assertStepLimit(steps, CONFIRM_STEP, CONFIRM_LIMIT, failures)
			val creditAndCompleteMillis = steps.filter { it.name == CREDIT_STEP || it.name == COMPLETE_STEP }.sumOf { it.durationMillis }
			if (creditAndCompleteMillis > CREDIT_AND_COMPLETE_LIMIT.toMillis()) {
				failures += "지갑 입금과 완료 단계의 합이 5분을 초과했습니다: ${creditAndCompleteMillis}ms"
			}
		}
		values.putIfAbsent("sla.profile", "NOT_APPLICABLE")
		return ValidationReport(failures.isEmpty(), values, failures)
	}

	private fun isTenMillionFixedBaseline(scenario: PerformanceScenario) =
		scenario.dataset.orderCount == TEN_MILLION &&
			scenario.dataset.sellerCount == ONE_HUNDRED_THOUSAND &&
			scenario.dataset.approvalTimeDistribution == ApprovalTimeDistribution.FIXED

	private fun assertStepLimit(
		steps: List<StepMeasurement>,
		stepName: String,
		limit: Duration,
		failures: MutableList<String>,
	) {
		val durationMillis = steps.singleOrNull { it.name == stepName }?.durationMillis
		if (durationMillis == null) {
			failures += "SLA 판정에 필요한 Step 결과가 없습니다: $stepName"
		} else if (durationMillis > limit.toMillis()) {
			failures += "$stepName 시간이 ${limit.toMinutes()}분을 초과했습니다: ${durationMillis}ms"
		}
	}

	private companion object {
		const val ONE_MILLION = 1_000_000
		const val TEN_MILLION = 10_000_000
		const val ONE_HUNDRED_THOUSAND = 100_000
		const val COLLECT_STEP = "collectSettlementDetailsStep"
		const val CONFIRM_STEP = "confirmSellerSettlementsStep"
		const val CREDIT_STEP = "creditSellerWalletsStep"
		const val COMPLETE_STEP = "completeSettlementRunStep"
		val ONE_MILLION_JOB_LIMIT: Duration = Duration.ofMinutes(4)
		val TEN_MILLION_JOB_LIMIT: Duration = Duration.ofMinutes(60)
		val COLLECT_LIMIT: Duration = Duration.ofMinutes(50)
		val CONFIRM_LIMIT: Duration = Duration.ofMinutes(5)
		val CREDIT_AND_COMPLETE_LIMIT: Duration = Duration.ofMinutes(5)
		val OFFICIAL_TIMEOUT: Duration = Duration.ofMinutes(65)
	}
}
