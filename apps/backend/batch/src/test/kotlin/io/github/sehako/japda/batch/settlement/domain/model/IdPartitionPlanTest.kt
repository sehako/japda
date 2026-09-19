package io.github.sehako.japda.batch.settlement.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

@DisplayName("ID 파티션 계획")
class IdPartitionPlanTest {
	@Test
	@DisplayName("ID 범위를 요청한 수 이하의 연속된 비중첩 범위로 나눈다")
	fun ID_범위를_요청한_수_이하의_연속된_비중첩_범위로_나눈다() {
		val plan = IdPartitionPlan.create(minId = 10L, maxId = 19L, partitionCount = 3)

		assertEquals(
			listOf(
				IdPartitionRange(10L, 14L, false),
				IdPartitionRange(14L, 17L, false),
				IdPartitionRange(17L, 20L, false),
			),
			plan.ranges,
		)
	}

	@Test
	@DisplayName("최소 ID와 최대 ID가 같으면 하나의 범위만 만든다")
	fun 최소_ID와_최대_ID가_같으면_하나의_범위만_만든다() {
		val plan = IdPartitionPlan.create(minId = 7L, maxId = 7L, partitionCount = 64)

		assertEquals(listOf(IdPartitionRange(7L, 8L, false)), plan.ranges)
	}

	@Test
	@DisplayName("ID 공간보다 파티션 수가 크면 빈 범위를 만들지 않는다")
	fun ID_공간보다_파티션_수가_크면_빈_범위를_만들지_않는다() {
		val plan = IdPartitionPlan.create(minId = 3L, maxId = 5L, partitionCount = 10)

		assertEquals(
			listOf(
				IdPartitionRange(3L, 4L, false),
				IdPartitionRange(4L, 5L, false),
				IdPartitionRange(5L, 6L, false),
			),
			plan.ranges,
		)
	}

	@Test
	@DisplayName("최대 ID가 Long 최댓값이면 마지막 범위만 상한을 포함한다")
	fun 최대_ID가_Long_최댓값이면_마지막_범위만_상한을_포함한다() {
		val plan = IdPartitionPlan.create(
			minId = Long.MAX_VALUE - 2,
			maxId = Long.MAX_VALUE,
			partitionCount = 2,
		)

		assertEquals(
			listOf(
				IdPartitionRange(Long.MAX_VALUE - 2, Long.MAX_VALUE, false),
				IdPartitionRange(Long.MAX_VALUE, Long.MAX_VALUE, true),
			),
			plan.ranges,
		)
		assertFalse(plan.ranges.first().endInclusive)
		assertTrue(plan.ranges.last().endInclusive)
	}

	@Test
	@DisplayName("대상이 없으면 빈 계획을 만든다")
	fun 대상이_없으면_빈_계획을_만든다() {
		val plan = IdPartitionPlan.create(minId = null, maxId = null, partitionCount = 64)

		assertEquals(null, plan.minId)
		assertEquals(null, plan.maxId)
		assertTrue(plan.ranges.isEmpty())
	}
}
