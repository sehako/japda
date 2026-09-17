package io.github.sehako.japda.batch.performance.dataset

import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import java.time.ZoneId
import java.util.Random
import java.util.UUID

object SyntheticDatasetFactory {
	fun create(scenario: PerformanceScenario): SyntheticDataset {
		val random = Random(scenario.dataset.randomSeed)
		val settlementDate = scenario.job.settlementDate
		val createdAt = settlementDate.minusDays(14).atStartOfDay(SEOUL_ZONE).toInstant()
		val orderCreatedAt = settlementDate.atStartOfDay(SEOUL_ZONE).toInstant()
		val approvedAt = settlementDate.atTime(12, 0).atZone(SEOUL_ZONE).toInstant()
		val sellers = (1..scenario.dataset.sellerCount).map { index ->
			SyntheticSeller(
				sellerId = index.toLong(),
				userId = index.toLong(),
				productId = index.toLong(),
				saleId = index.toLong(),
				providerSubject = "performance-${scenario.dataset.randomSeed}-$index",
				email = "performance-${scenario.dataset.randomSeed}-$index@example.invalid",
				createdAt = createdAt,
			)
		}.shuffled(random)
		val sellerById = sellers.associateBy { it.sellerId }
		val orders = (1..scenario.dataset.orderCount).map { index ->
			val sellerId = ((index - 1) % scenario.dataset.sellerCount + 1).toLong()
			val seller = sellerById.getValue(sellerId)
			SyntheticOrder(
				id = index.toLong(),
				saleId = seller.saleId,
				sellerId = sellerId,
				buyerId = index.toLong(),
				idempotencyKey = randomUuid(random),
				paymentOrderId = "performance_${scenario.dataset.randomSeed}_$index",
				grossAmount = scenario.dataset.grossAmount,
				createdAt = orderCreatedAt,
				expiresAt = orderCreatedAt.plusSeconds(600),
			)
		}.shuffled(random)
		val payments = orders.map { order ->
			SyntheticPayment(
				id = order.id,
				orderId = order.id,
				paymentKey = "performance-payment-${scenario.dataset.randomSeed}-${order.id}",
				tossIdempotencyKey = randomUuid(random).toString(),
				grossAmount = order.grossAmount,
				createdAt = order.createdAt,
				approvedAt = approvedAt,
			)
		}.shuffled(random)

		return SyntheticDataset(
			saleDate = settlementDate,
			saleDayCapacity = scenario.dataset.sellerCount,
			sellers = sellers,
			orders = orders,
			payments = payments,
			expectedSettlement = expectedSettlement(scenario, sellers, orders),
		)
	}

	private fun expectedSettlement(
		scenario: PerformanceScenario,
		sellers: List<SyntheticSeller>,
		orders: List<SyntheticOrder>,
	): ExpectedSettlement {
		val orderCounts = orders.groupingBy { it.sellerId }.eachCount()
		val expectedSellers = sellers.map { seller ->
			val orderCount = orderCounts.getValue(seller.sellerId)
			val gross = Math.multiplyExact(scenario.dataset.grossAmount, orderCount.toLong())
			val fee = gross.toBigInteger()
				.multiply(scenario.job.platformFeeRateBps.toBigInteger())
				.divide(FEE_RATE_DENOMINATOR)
				.longValueExact()
			ExpectedSellerSettlement(
				sellerId = seller.sellerId,
				recipientUserId = seller.userId,
				orderCount = orderCount,
				grossAmount = gross,
				platformFeeAmount = fee,
				netAmount = gross - fee,
			)
		}.sortedBy { it.sellerId }
		return ExpectedSettlement(
			detailCount = orders.size.toLong(),
			sellerSettlementCount = sellers.size.toLong(),
			grossAmount = expectedSellers.sumOf { it.grossAmount },
			platformFeeAmount = expectedSellers.sumOf { it.platformFeeAmount },
			netAmount = expectedSellers.sumOf { it.netAmount },
			sellers = expectedSellers,
		)
	}

	private fun randomUuid(random: Random) = UUID(random.nextLong(), random.nextLong())

	private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
	private val FEE_RATE_DENOMINATOR = 10_000.toBigInteger()
}
