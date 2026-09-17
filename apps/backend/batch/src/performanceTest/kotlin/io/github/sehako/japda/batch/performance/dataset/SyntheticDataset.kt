package io.github.sehako.japda.batch.performance.dataset

import java.time.Instant
import java.time.LocalDate

data class SyntheticDataset(
	val saleDate: LocalDate,
	val sellerCount: Int,
	val orderCount: Int,
	val randomSeed: Long,
	val grossAmount: Long,
	val entityCreatedAt: Instant,
	val orderCreatedAt: Instant,
	val approvedAt: Instant,
	val expectedSettlement: ExpectedSettlement,
)

data class ExpectedSettlement(
	val detailCount: Long,
	val sellerSettlementCount: Long,
	val grossAmount: Long,
	val platformFeeAmount: Long,
	val netAmount: Long,
)
