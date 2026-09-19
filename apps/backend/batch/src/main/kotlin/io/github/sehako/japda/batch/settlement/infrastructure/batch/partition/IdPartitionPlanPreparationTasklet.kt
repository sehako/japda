package io.github.sehako.japda.batch.settlement.infrastructure.batch.partition

import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus

data class IdBounds(
	val minId: Long?,
	val maxId: Long?,
) {
	init {
		require((minId == null) == (maxId == null)) { "최소 ID와 최대 ID는 함께 존재해야 합니다." }
	}
}

class IdPartitionPlanPreparationTasklet(
	private val planService: IdPartitionPlanService,
	private val type: PartitionPlanType,
	private val partitionCount: Int,
	private val findBounds: () -> IdBounds,
) : Tasklet {
	override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
		val context = chunkContext.stepContext.stepExecution.jobExecution.executionContext
		val planKey = "${type.keyPrefix}.partition.planned"
		if (context.containsKey(planKey)) {
			planService.getOrCreate(context, type, null, null, partitionCount)
		} else {
			val bounds = findBounds()
			planService.getOrCreate(context, type, bounds.minId, bounds.maxId, partitionCount)
		}
		return RepeatStatus.FINISHED
	}
}
