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
	val randomSeed: Long,
	val grossAmount: Long,
)

data class SettlementJobScenario(
	val settlementDate: LocalDate,
	val platformFeeRateBps: Int,
	val timeout: Duration,
)
