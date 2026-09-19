package io.github.sehako.japda.batch.settlement.infrastructure.batch.partition

import org.springframework.batch.core.partition.Partitioner
import org.springframework.batch.infrastructure.item.ExecutionContext

class StoredIdPartitioner(
	private val jobExecutionContext: ExecutionContext,
	private val type: PartitionPlanType,
	private val planService: IdPartitionPlanService,
) : Partitioner {
	override fun partition(gridSize: Int): Map<String, ExecutionContext> =
		planService.getOrCreate(jobExecutionContext, type, null, null, 1).ranges
			.mapIndexed { index, range ->
				val name = "${type.keyPrefix}Partition${index.toString().padStart(3, '0')}"
				name to ExecutionContext().apply {
					putLong(START_INCLUSIVE_KEY, range.startInclusive)
					putLong(END_EXCLUSIVE_KEY, range.endExclusive)
					put(END_INCLUSIVE_KEY, range.endInclusive)
				}
			}
			.toMap(linkedMapOf())

	companion object {
		const val START_INCLUSIVE_KEY = "partition.startInclusive"
		const val END_EXCLUSIVE_KEY = "partition.endExclusive"
		const val END_INCLUSIVE_KEY = "partition.endInclusive"
	}
}
