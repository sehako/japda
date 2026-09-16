package io.github.sehako.japda.batch.settlement.application.tasklet

import io.github.sehako.japda.batch.settlement.exception.SettlementCreditErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementCreditException
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStatus
import java.time.Clock
import java.time.Instant
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus

class CompleteSettlementRunTasklet(
	private val settlementRunRepository: SettlementRunJdbcRepository,
	private val sellerSettlementRepository: SellerSettlementJdbcRepository,
	private val clock: Clock,
) : Tasklet {
	override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
		val settlementRunId = contribution.stepExecution.jobExecution.executionContext
			.getLong(PrepareSettlementRunTasklet.SETTLEMENT_RUN_ID_CONTEXT_KEY)
		val run = settlementRunRepository.findByIdForUpdate(settlementRunId)
			?: fail(SettlementCreditErrorType.RUN_NOT_FOUND, "SettlementRun을 찾을 수 없습니다: settlementRunId=$settlementRunId")
		if (run.status !in setOf(SettlementRunStatus.CONFIRMED, SettlementRunStatus.COMPLETED)) {
			fail(SettlementCreditErrorType.INVALID_RUN_STATUS, "완료할 수 없는 SettlementRun 상태입니다: settlementRunId=$settlementRunId, status=${run.status}")
		}
		val mismatchSellerId = sellerSettlementRepository.findCreditResultMismatchSellerId(settlementRunId)
		if (mismatchSellerId != null) {
			fail(SettlementCreditErrorType.CREDIT_RESULT_MISMATCH, "판매자별 입금 결과가 일치하지 않습니다: settlementRunId=$settlementRunId, sellerId=$mismatchSellerId", mismatchSellerId)
		}
		if (run.status == SettlementRunStatus.COMPLETED) {
			if (run.completedAt == null) fail(SettlementCreditErrorType.CREDIT_RESULT_MISMATCH, "정산 완료 시각이 없습니다: settlementRunId=$settlementRunId")
		} else {
			settlementRunRepository.markCompleted(settlementRunId, Instant.now(clock))
		}
		return RepeatStatus.FINISHED
	}

	private fun fail(errorType: SettlementCreditErrorType, message: String, sellerId: Long? = null): Nothing =
		throw SettlementCreditException(errorType, message, sellerId = sellerId)
}
