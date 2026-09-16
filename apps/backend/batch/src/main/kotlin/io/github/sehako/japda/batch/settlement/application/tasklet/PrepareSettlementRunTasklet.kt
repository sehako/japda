package io.github.sehako.japda.batch.settlement.application.tasklet

import io.github.sehako.japda.batch.settlement.infrastructure.batch.validation.DailySellerSettlementJobParametersValidator.Companion.PLATFORM_FEE_RATE_BPS_PARAMETER
import io.github.sehako.japda.batch.settlement.infrastructure.batch.validation.DailySellerSettlementJobParametersValidator.Companion.SETTLEMENT_DATE_PARAMETER
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStateException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus

class PrepareSettlementRunTasklet(
	private val settlementRunRepository: SettlementRunJdbcRepository,
	private val clock: Clock,
) : Tasklet {
	override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
		val parameters = contribution.stepExecution.jobParameters
		val settlementDate = LocalDate.parse(parameters.getString(SETTLEMENT_DATE_PARAMETER))
		val platformFeeRateBps = parameters.getLong(PLATFORM_FEE_RATE_BPS_PARAMETER)!!.toInt()
		val existing = settlementRunRepository.findBySettlementDate(settlementDate)
		val settlementRun = existing ?: settlementRunRepository.create(settlementDate, platformFeeRateBps, Instant.now(clock))
		if (settlementRun.platformFeeRateBps != platformFeeRateBps) {
			throw SettlementRunStateException("저장된 플랫폼 수수료율과 재시작 parameter가 다릅니다: settlementDate=$settlementDate")
		}
		contribution.stepExecution.executionContext.putLong(SETTLEMENT_RUN_ID_CONTEXT_KEY, settlementRun.id)
		return RepeatStatus.FINISHED
	}

	companion object {
		const val SETTLEMENT_RUN_ID_CONTEXT_KEY = "settlementRunId"
	}
}
