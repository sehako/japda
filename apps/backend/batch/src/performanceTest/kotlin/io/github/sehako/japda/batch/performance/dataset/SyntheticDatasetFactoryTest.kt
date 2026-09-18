package io.github.sehako.japda.batch.performance.dataset

import io.github.sehako.japda.batch.performance.scenario.DatasetScenario
import io.github.sehako.japda.batch.performance.scenario.ApprovalTimeDistribution
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import io.github.sehako.japda.batch.performance.scenario.SettlementJobScenario
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName

@DisplayName("정산 성능 테스트 합성 데이터 계산")
class SyntheticDatasetFactoryTest {
	@Test
	@DisplayName("전체 행 목록 없이 SQL 생성에 필요한 값만 계산한다")
	fun 전체_행_목록_없이_SQL_생성에_필요한_값만_계산한다() {
		val dataset = SyntheticDatasetFactory.create(scenario(seed = 42))

		assertEquals(LocalDate.of(2026, 9, 15), dataset.saleDate)
		assertEquals(3, dataset.sellerCount)
		assertEquals(7, dataset.orderCount)
		assertEquals(42L, dataset.randomSeed)
		assertEquals(10_000L, dataset.grossAmount)
		assertEquals(Instant.parse("2026-08-31T15:00:00Z"), dataset.entityCreatedAt)
		assertEquals(Instant.parse("2026-09-14T15:00:00Z"), dataset.orderCreatedAt)
		assertEquals(Instant.parse("2026-09-15T03:00:00Z"), dataset.approvedAt)
	}

	@Test
	@DisplayName("UNIFORM 분포는 정산일 전체 범위의 결정론적 승인 시각을 사용한다")
	fun UNIFORM_분포는_정산일_전체_범위의_결정론적_승인_시각을_사용한다() {
		val dataset = SyntheticDatasetFactory.create(scenario(approvalTimeDistribution = ApprovalTimeDistribution.UNIFORM))

		assertEquals(ApprovalTimeDistribution.UNIFORM, dataset.approvalTimeDistribution)
		assertEquals(Instant.parse("2026-09-14T15:00:00Z"), dataset.approvalTimeStart)
		assertEquals(Instant.parse("2026-09-15T15:00:00Z"), dataset.approvalTimeEndExclusive)
	}

	@Test
	@DisplayName("나머지 주문을 균등 분배하고 판매자 단위로 수수료를 내림한다")
	fun 나머지_주문을_균등_분배하고_판매자_단위로_수수료를_내림한다() {
		val dataset = SyntheticDatasetFactory.create(
			scenario(sellerCount = 3, orderCount = 8, grossAmount = 1, feeRateBps = 5_000),
		)

		assertEquals(
			ExpectedSettlement(
				detailCount = 8,
				sellerSettlementCount = 3,
				grossAmount = 8,
				platformFeeAmount = 3,
				netAmount = 5,
			),
			dataset.expectedSettlement,
		)
	}

	private fun scenario(
		sellerCount: Int = 3,
		orderCount: Int = 7,
		seed: Long = 1,
		grossAmount: Long = 10_000,
		feeRateBps: Int = 1_000,
		approvalTimeDistribution: ApprovalTimeDistribution = ApprovalTimeDistribution.FIXED,
	) = PerformanceScenario(
		dataset = DatasetScenario(sellerCount, orderCount, 2, seed, grossAmount, approvalTimeDistribution),
		job = SettlementJobScenario(LocalDate.of(2026, 9, 15), feeRateBps, Duration.ofMinutes(30)),
		warmupIterations = 0,
		measurementIterations = 1,
		resourceSamplingInterval = Duration.ofMillis(100),
	)
}
