package io.github.sehako.japda.batch.settlement.infrastructure.batch.partition

import io.github.sehako.japda.batch.settlement.domain.model.IdPartitionRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.springframework.batch.infrastructure.item.ExecutionContext

@DisplayName("ID 파티션 계획 저장과 복원")
class IdPartitionPlanServiceTest {
	private val service = IdPartitionPlanService()

	@Test
	@DisplayName("collection과 credit 계획을 종류별 고정 key의 기본 타입으로 저장한다")
	fun collection과_credit_계획을_종류별_고정_key의_기본_타입으로_저장한다() {
		val context = ExecutionContext()

		service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 12L, 2)
		service.getOrCreate(context, PartitionPlanType.CREDIT, 20L, 20L, 1)

		assertEquals(true, context.get("collection.partition.planned"))
		assertEquals(10L, context.get("collection.partition.minId"))
		assertEquals(12L, context.get("collection.partition.maxId"))
		assertEquals(2, context.get("collection.partition.count"))
		assertEquals(10L, context.get("collection.partition.000.startInclusive"))
		assertEquals(12L, context.get("collection.partition.000.endExclusive"))
		assertEquals(false, context.get("collection.partition.000.endInclusive"))
		assertEquals(12L, context.get("collection.partition.001.startInclusive"))
		assertEquals(13L, context.get("collection.partition.001.endExclusive"))
		assertEquals(false, context.get("collection.partition.001.endInclusive"))
		assertEquals(20L, context.get("credit.partition.000.startInclusive"))
		assertEquals(21L, context.get("credit.partition.000.endExclusive"))
		assertEquals(false, context.get("credit.partition.000.endInclusive"))
		assertTrue(context.toMap().values.all { it is Long || it is Int || it is Boolean || it is String })
	}

	@Test
	@DisplayName("저장된 계획은 현재 범위와 파티션 설정이 바뀌어도 그대로 복원한다")
	fun 저장된_계획은_현재_범위와_파티션_설정이_바뀌어도_그대로_복원한다() {
		val context = ExecutionContext()
		val saved = service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 19L, 3)

		val restored = service.getOrCreate(context, PartitionPlanType.COLLECTION, 1L, 1_000L, 64)

		assertEquals(saved, restored)
		assertEquals(
			listOf(
				IdPartitionRange(10L, 14L, false),
				IdPartitionRange(14L, 17L, false),
				IdPartitionRange(17L, 20L, false),
			),
			restored.ranges,
		)
	}

	@Test
	@DisplayName("빈 계획도 재시작에서 다시 계산하지 않고 복원한다")
	fun 빈_계획도_재시작에서_다시_계산하지_않고_복원한다() {
		val context = ExecutionContext()
		service.getOrCreate(context, PartitionPlanType.CREDIT, null, null, 64)

		val restored = service.getOrCreate(context, PartitionPlanType.CREDIT, 1L, 100L, 8)

		assertTrue(restored.ranges.isEmpty())
		assertEquals(0, context.get("credit.partition.count"))
	}

	@Test
	@DisplayName("저장된 계획에서 일부 경계가 누락되면 복원을 거절한다")
	fun 저장된_계획에서_일부_경계가_누락되면_복원을_거절한다() {
		val context = savedContext()
		context.remove("collection.partition.001.endExclusive")

		assertFailsWith<IllegalStateException> {
			service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 19L, 3)
		}
	}

	@Test
	@DisplayName("저장된 계획의 범위가 중첩되면 복원을 거절한다")
	fun 저장된_계획의_범위가_중첩되면_복원을_거절한다() {
		val context = savedContext()
		context.putLong("collection.partition.001.startInclusive", 13L)

		assertFailsWith<IllegalStateException> {
			service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 19L, 3)
		}
	}

	@Test
	@DisplayName("저장된 계획의 범위가 누락되면 복원을 거절한다")
	fun 저장된_계획의_범위가_누락되면_복원을_거절한다() {
		val context = savedContext()
		context.putLong("collection.partition.001.startInclusive", 15L)

		assertFailsWith<IllegalStateException> {
			service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 19L, 3)
		}
	}

	@Test
	@DisplayName("저장된 계획의 시작과 끝이 역전되면 복원을 거절한다")
	fun 저장된_계획의_시작과_끝이_역전되면_복원을_거절한다() {
		val context = savedContext()
		context.putLong("collection.partition.001.endExclusive", 13L)

		assertFailsWith<IllegalStateException> {
			service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 19L, 3)
		}
	}

	@Test
	@DisplayName("저장된 계획의 메타데이터와 경계가 다르면 복원을 거절한다")
	fun 저장된_계획의_메타데이터와_경계가_다르면_복원을_거절한다() {
		val context = savedContext()
		context.putLong("collection.partition.minId", 9L)

		assertFailsWith<IllegalStateException> {
			service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 19L, 3)
		}
	}

	@Test
	@DisplayName("계획 표식 없이 일부 key만 있으면 새 계획으로 덮어쓰지 않는다")
	fun 계획_표식_없이_일부_key만_있으면_새_계획으로_덮어쓰지_않는다() {
		val context = ExecutionContext().apply {
			putInt("collection.partition.count", 1)
		}

		assertFailsWith<IllegalStateException> {
			service.getOrCreate(context, PartitionPlanType.COLLECTION, 1L, 1L, 1)
		}
	}

	private fun savedContext() = ExecutionContext().also { context ->
		service.getOrCreate(context, PartitionPlanType.COLLECTION, 10L, 19L, 3)
	}
}
