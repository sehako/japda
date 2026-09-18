package io.github.sehako.japda.batch.performance.scenario

import java.time.Duration
import java.time.LocalDate

data class PerformanceScenario(
	val dataset: DatasetScenario,
	val job: SettlementJobScenario,
	val warmupIterations: Int,
	val measurementIterations: Int,
	val resourceSamplingInterval: Duration,
)

data class DatasetScenario(
	val sellerCount: Int,
	val orderCount: Int,
	val generationBatchSize: Int,
	val randomSeed: Long,
	val grossAmount: Long,
	val approvalTimeDistribution: ApprovalTimeDistribution = ApprovalTimeDistribution.FIXED,
)

enum class ApprovalTimeDistribution {
	FIXED,
	UNIFORM,
}

data class SettlementJobScenario(
	val settlementDate: LocalDate,
	val platformFeeRateBps: Int,
	val timeout: Duration,
)
