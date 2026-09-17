package io.github.sehako.japda.batch.performance.dataset

import io.github.sehako.japda.batch.performance.scenario.DatasetScenario
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import io.github.sehako.japda.batch.performance.scenario.SettlementJobScenario
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

@DisplayName("정산 성능 테스트 합성 데이터 계산")
class SyntheticDatasetFactoryTest {
	@Test
	@DisplayName("같은 seed와 설정은 같은 데이터와 예상값을 만든다")
	fun 같은_seed와_설정은_같은_데이터와_예상값을_만든다() {
		val scenario = scenario(seed = 42)

		val first = SyntheticDatasetFactory.create(scenario)
		val second = SyntheticDatasetFactory.create(scenario)

		assertEquals(first, second)
	}

	@Test
	@DisplayName("seed가 다르면 식별자와 insert 순서가 달라진다")
	fun seed가_다르면_식별자와_insert_순서가_달라진다() {
		val first = SyntheticDatasetFactory.create(scenario(seed = 1))
		val second = SyntheticDatasetFactory.create(scenario(seed = 2))

		assertNotEquals(first.orders.map { it.idempotencyKey }, second.orders.map { it.idempotencyKey })
		assertNotEquals(first.orders.map { it.id }, second.orders.map { it.id })
	}

	@Test
	@DisplayName("나머지 주문도 모든 판매자에게 최대 한 건 차이로 배정한다")
	fun 나머지_주문도_모든_판매자에게_최대_한_건_차이로_배정한다() {
		val dataset = SyntheticDatasetFactory.create(scenario(sellerCount = 3, orderCount = 8))

		val counts = dataset.orders.groupingBy { it.sellerId }.eachCount().values

		assertEquals(3, counts.size)
		assertEquals(8, counts.sum())
		assertTrue(counts.max() - counts.min() <= 1)
	}

	@Test
	@DisplayName("판매자 단위 수수료 내림 규칙으로 예상 정산값을 계산한다")
	fun 판매자_단위_수수료_내림_규칙으로_예상_정산값을_계산한다() {
		val dataset = SyntheticDatasetFactory.create(
			scenario(sellerCount = 2, orderCount = 3, grossAmount = 101, feeRateBps = 333),
		)

		assertEquals(3, dataset.expectedSettlement.detailCount)
		assertEquals(2, dataset.expectedSettlement.sellerSettlementCount)
		assertEquals(303L, dataset.expectedSettlement.grossAmount)
		assertEquals(9L, dataset.expectedSettlement.platformFeeAmount)
		assertEquals(294L, dataset.expectedSettlement.netAmount)
		assertEquals(listOf(2, 1), dataset.expectedSettlement.sellers.sortedBy { it.sellerId }.map { it.orderCount })
	}

	private fun scenario(
		sellerCount: Int = 3,
		orderCount: Int = 7,
		seed: Long = 1,
		grossAmount: Long = 10_000,
		feeRateBps: Int = 1_000,
	) = PerformanceScenario(
		dataset = DatasetScenario(sellerCount, orderCount, seed, grossAmount),
		job = SettlementJobScenario(LocalDate.of(2026, 9, 15), feeRateBps, Duration.ofMinutes(30)),
		warmupIterations = 0,
		measurementIterations = 1,
		resourceSamplingInterval = Duration.ofMillis(100),
	)
}
