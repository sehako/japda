package io.github.sehako.japda.batch.settlement.infrastructure.batch.partition

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.infrastructure.item.ExecutionContext

@DisplayName("저장된 ID 계획 Partitioner")
class StoredIdPartitionerTest {
	private val planService = IdPartitionPlanService()

	@Test
	@DisplayName("저장된 범위를 고정 이름의 worker ExecutionContext로 내보낸다")
	fun 저장된_범위_고정_이름_worker_ExecutionContext로_내보낸다() {
		val jobContext = ExecutionContext()
		planService.getOrCreate(jobContext, PartitionPlanType.COLLECTION, 10L, 14L, 2)

		val partitions = StoredIdPartitioner(jobContext, PartitionPlanType.COLLECTION, planService).partition(8)

		assertEquals(listOf("collectionPartition000", "collectionPartition001"), partitions.keys.toList())
		assertEquals(10L, partitions.getValue("collectionPartition000").getLong("partition.startInclusive"))
		assertEquals(13L, partitions.getValue("collectionPartition000").getLong("partition.endExclusive"))
		assertEquals(false, partitions.getValue("collectionPartition000").get("partition.endInclusive"))
		assertEquals(13L, partitions.getValue("collectionPartition001").getLong("partition.startInclusive"))
		assertEquals(15L, partitions.getValue("collectionPartition001").getLong("partition.endExclusive"))
	}

	@Test
	@DisplayName("빈 계획은 worker를 만들지 않는다")
	fun 빈_계획_worker를_만들지_않는다() {
		val jobContext = ExecutionContext()
		planService.getOrCreate(jobContext, PartitionPlanType.CREDIT, null, null, 64)

		val partitions = StoredIdPartitioner(jobContext, PartitionPlanType.CREDIT, planService).partition(8)

		assertTrue(partitions.isEmpty())
	}
}
