package io.github.sehako.japda.batch.settlement.infrastructure.batch.partition

import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.JobInstance
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.scope.context.StepContext
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.core.step.StepContribution
import org.mockito.Mockito.mock

@DisplayName("ID 파티션 계획 준비 Tasklet")
class IdPartitionPlanPreparationTaskletTest {
	@Test
	@DisplayName("최초 실행은 조회한 최소 최대 ID로 계획을 Job ExecutionContext에 저장한다")
	fun 최초_실행_조회한_최소_최대_ID로_계획을_Job_ExecutionContext에_저장한다() {
		val execution = jobExecution()
		val tasklet = IdPartitionPlanPreparationTasklet(
			IdPartitionPlanService(),
			PartitionPlanType.COLLECTION,
			2,
		) { IdBounds(10L, 12L) }

		tasklet.execute(mock(StepContribution::class.java), ChunkContext(StepContext(StepExecution(1L, "prepare", execution))))

		assertEquals(2, execution.executionContext.getInt("collection.partition.count"))
		assertEquals(10L, execution.executionContext.getLong("collection.partition.000.startInclusive"))
		assertEquals(13L, execution.executionContext.getLong("collection.partition.001.endExclusive"))
	}

	@Test
	@DisplayName("재시작은 저장된 계획을 복원하고 ID 경계를 다시 조회하지 않는다")
	fun 재시작_저장된_계획을_복원하고_ID_경계를_다시_조회하지_않는다() {
		val execution = jobExecution()
		val planService = IdPartitionPlanService()
		planService.getOrCreate(execution.executionContext, PartitionPlanType.CREDIT, 20L, 29L, 3)
		var queried = false
		val tasklet = IdPartitionPlanPreparationTasklet(planService, PartitionPlanType.CREDIT, 64) {
			queried = true
			IdBounds(1L, 100L)
		}

		tasklet.execute(mock(StepContribution::class.java), ChunkContext(StepContext(StepExecution(1L, "prepare", execution))))

		assertEquals(false, queried)
		assertEquals(3, execution.executionContext.getInt("credit.partition.count"))
	}

	private fun jobExecution() = JobExecution(1L, JobInstance(1L, "job"), JobParameters())
}
