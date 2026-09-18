package io.github.sehako.japda.batch.performance.dataset

import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import java.time.ZoneId

object SyntheticDatasetFactory {
	fun create(scenario: PerformanceScenario): SyntheticDataset {
		val dataset = scenario.dataset
		val settlementDate = scenario.job.settlementDate
		val entityCreatedAt = settlementDate.minusDays(14).atStartOfDay(SEOUL_ZONE).toInstant()
		val orderCreatedAt = settlementDate.atStartOfDay(SEOUL_ZONE).toInstant()
		val approvedAt = settlementDate.atTime(12, 0).atZone(SEOUL_ZONE).toInstant()
		val approvalTimeStart = orderCreatedAt
		val approvalTimeEndExclusive = settlementDate.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant()

		return SyntheticDataset(
			saleDate = settlementDate,
			sellerCount = dataset.sellerCount,
			orderCount = dataset.orderCount,
			randomSeed = dataset.randomSeed,
			grossAmount = dataset.grossAmount,
			entityCreatedAt = entityCreatedAt,
			orderCreatedAt = orderCreatedAt,
			approvedAt = approvedAt,
			approvalTimeDistribution = dataset.approvalTimeDistribution,
			approvalTimeStart = approvalTimeStart,
			approvalTimeEndExclusive = approvalTimeEndExclusive,
			expectedSettlement = expectedSettlement(scenario),
		)
	}

	private fun expectedSettlement(scenario: PerformanceScenario): ExpectedSettlement {
		val dataset = scenario.dataset
		val baseOrderCount = dataset.orderCount / dataset.sellerCount
		val remainderSellerCount = dataset.orderCount % dataset.sellerCount
		val baseSellerCount = dataset.sellerCount - remainderSellerCount
		val baseFee = sellerFee(dataset.grossAmount, baseOrderCount, scenario.job.platformFeeRateBps)
		val remainderFee = sellerFee(dataset.grossAmount, baseOrderCount + 1, scenario.job.platformFeeRateBps)
		val totalGross = Math.multiplyExact(dataset.grossAmount, dataset.orderCount.toLong())
		val totalFee = Math.addExact(
			Math.multiplyExact(baseFee, baseSellerCount.toLong()),
			Math.multiplyExact(remainderFee, remainderSellerCount.toLong()),
		)
		return ExpectedSettlement(
			detailCount = dataset.orderCount.toLong(),
			sellerSettlementCount = dataset.sellerCount.toLong(),
			grossAmount = totalGross,
			platformFeeAmount = totalFee,
			netAmount = totalGross - totalFee,
		)
	}

	private fun sellerFee(grossAmount: Long, orderCount: Int, feeRateBps: Int): Long = grossAmount.toBigInteger()
		.multiply(orderCount.toBigInteger())
		.multiply(feeRateBps.toBigInteger())
		.divide(FEE_RATE_DENOMINATOR)
		.longValueExact()

	private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
	private val FEE_RATE_DENOMINATOR = 10_000.toBigInteger()
}
