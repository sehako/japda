package io.github.sehako.japda.batch.settlement.application.tasklet

import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStateException
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStatus
import java.time.Clock
import java.time.Instant
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus

class CompleteSettlementCollectionTasklet(
	private val settlementRunRepository: SettlementRunJdbcRepository,
	private val clock: Clock,
) : Tasklet {
	override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
		val settlementRunId = contribution.stepExecution.jobExecution.executionContext
			.getLong(PrepareSettlementRunTasklet.SETTLEMENT_RUN_ID_CONTEXT_KEY)
		val settlementRun = settlementRunRepository.findById(settlementRunId)
			?: throw SettlementRunStateException("SettlementRun을 찾을 수 없습니다: settlementRunId=$settlementRunId")
		val aggregate = settlementRunRepository.aggregateDetails(settlementRunId)
		when (settlementRun.status) {
			SettlementRunStatus.COLLECTING -> settlementRunRepository.markCollected(settlementRunId, aggregate, Instant.now(clock))
			SettlementRunStatus.COLLECTED -> if (
				settlementRun.collectedCount != aggregate.collectedCount ||
				settlementRun.collectedAmount != aggregate.collectedAmount
			) {
				throw SettlementRunStateException("완료된 SettlementRun의 저장 집계와 상세 집계가 다릅니다: settlementRunId=$settlementRunId")
			}
		}
		return RepeatStatus.FINISHED
	}
}
