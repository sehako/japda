package io.github.sehako.japda.batch.settlement.application.tasklet

import io.github.sehako.japda.batch.settlement.exception.SettlementConfirmationErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementConfirmationException
import io.github.sehako.japda.batch.settlement.domain.model.SettlementEntryBounds
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementAggregate
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunSnapshot
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.slf4j.LoggerFactory

class ConfirmSellerSettlementsTasklet(
	private val settlementRunRepository: SettlementRunJdbcRepository,
	private val sellerSettlementRepository: SellerSettlementJdbcRepository,
	private val clock: Clock,
) : Tasklet {
	override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
		val stepExecution = contribution.stepExecution
		val jobExecution = stepExecution.jobExecution
		val settlementRunId = jobExecution.executionContext
			.getLong(PrepareSettlementRunTasklet.SETTLEMENT_RUN_ID_CONTEXT_KEY)
		return try {
			executeConfirmation(settlementRunId, SettlementEntryBounds.from(jobExecution.executionContext))
		} catch (exception: RuntimeException) {
			val confirmationException = exception as? SettlementConfirmationException
			logger.error(
				"판매자 정산 확정 실패: jobName={}, settlementDate={}, settlementRunId={}, jobExecutionId={}, stepExecutionId={}, sellerId={}, errorType={}",
				jobExecution.jobInstance.jobName,
				jobExecution.jobParameters.getString("settlementDate"),
				settlementRunId,
				jobExecution.id,
				stepExecution.id,
				confirmationException?.sellerId,
				confirmationException?.errorType ?: exception.javaClass.simpleName,
			)
			throw exception
		}
	}

	private fun executeConfirmation(settlementRunId: Long, entryBounds: SettlementEntryBounds): RepeatStatus {
		val settlementRun = settlementRunRepository.findByIdForUpdate(settlementRunId)
			?: fail(SettlementConfirmationErrorType.RUN_NOT_FOUND, "SettlementRun을 찾을 수 없습니다: settlementRunId=$settlementRunId")
		when (settlementRun.status) {
			SettlementRunStatus.COLLECTED,
			SettlementRunStatus.CONFIRMED,
			-> confirmOrValidate(settlementRun, entryBounds)
			SettlementRunStatus.COLLECTING,
			SettlementRunStatus.COMPLETED,
			-> fail(
				SettlementConfirmationErrorType.INVALID_RUN_STATUS,
				"확정할 수 없는 SettlementRun 상태입니다: settlementRunId=$settlementRunId, status=${settlementRun.status}",
			)
		}
		return RepeatStatus.FINISHED
	}

	private fun confirmOrValidate(settlementRun: SettlementRunSnapshot, entryBounds: SettlementEntryBounds) {
		sellerSettlementRepository.createTemporarySellerAggregates(settlementRun.settlementDate, entryBounds)
		validateDetailAggregate(settlementRun, sellerSettlementRepository.aggregateTemporarySellerAggregates())
		validateRecipientsAndRanges(settlementRun)
		if (settlementRun.status == SettlementRunStatus.CONFIRMED) {
			validateConfirmed(settlementRun)
			return
		}
		if (sellerSettlementRepository.countBySettlementRunId(settlementRun.id) != 0L) {
			fail(SettlementConfirmationErrorType.EXISTING_RESULT, "COLLECTED 실행에 기존 판매자별 정산 결과가 있습니다: settlementRunId=${settlementRun.id}")
		}
		val confirmedAt = Instant.now(clock)
		sellerSettlementRepository.insertTemporaryAggregates(settlementRun.id, settlementRun.platformFeeRateBps, confirmedAt)
		validateSavedResults(settlementRun)
		try {
			settlementRunRepository.markConfirmed(settlementRun.id, confirmedAt)
		} catch (exception: RuntimeException) {
			throw SettlementConfirmationException(
				SettlementConfirmationErrorType.STATE_TRANSITION_FAILED,
				"SettlementRun 확정 상태 전환에 실패했습니다: settlementRunId=${settlementRun.id}",
				cause = exception,
			)
		}
	}

	private fun validateConfirmed(settlementRun: SettlementRunSnapshot) {
		if (settlementRun.confirmationCompletedAt == null) {
			fail(SettlementConfirmationErrorType.CONFIRMED_RESULT_MISMATCH, "확정 완료 시각이 없습니다: settlementRunId=${settlementRun.id}")
		}
		validateSavedResults(settlementRun, SettlementConfirmationErrorType.CONFIRMED_RESULT_MISMATCH)
	}

	private fun validateSavedResults(
		settlementRun: SettlementRunSnapshot,
		errorType: SettlementConfirmationErrorType = SettlementConfirmationErrorType.SAVED_RESULT_DETAIL_MISMATCH,
	) {
		val sellerId = sellerSettlementRepository.findTemporarySavedResultMismatchSellerId(
			settlementRun.id,
			settlementRun.platformFeeRateBps,
		)
		if (sellerId != null) {
			fail(
				errorType,
				"판매자별 저장 결과가 상세 계산과 다릅니다: settlementRunId=${settlementRun.id}, sellerId=$sellerId",
				sellerId,
			)
		}
	}

	private fun validateDetailAggregate(settlementRun: SettlementRunSnapshot, aggregate: SettlementAggregate) {
		if (
			aggregate.itemCount.compareTo(BigDecimal.valueOf(settlementRun.collectedCount)) != 0 ||
			aggregate.totalAmount.compareTo(BigDecimal.valueOf(settlementRun.collectedAmount)) != 0
		) {
			fail(SettlementConfirmationErrorType.DETAIL_AGGREGATE_MISMATCH, "수집 집계와 실제 상세 집계가 다릅니다: settlementRunId=${settlementRun.id}")
		}
	}

	private fun validateRecipientsAndRanges(settlementRun: SettlementRunSnapshot) {
		val recipientMismatchSellerId = sellerSettlementRepository.findTemporaryRecipientMismatchSellerId()
		if (recipientMismatchSellerId != null) {
			fail(
				SettlementConfirmationErrorType.RECIPIENT_MISMATCH,
				"한 판매자에 여러 지급 대상이 존재합니다: settlementRunId=${settlementRun.id}, sellerId=$recipientMismatchSellerId",
				recipientMismatchSellerId,
			)
		}
		val outOfRangeSellerId = sellerSettlementRepository.findTemporaryAmountOutOfRangeSellerId(settlementRun.platformFeeRateBps)
		if (outOfRangeSellerId != null) {
			fail(
				SettlementConfirmationErrorType.AMOUNT_OUT_OF_RANGE,
				"판매자별 계산 금액이 BIGINT 범위를 벗어납니다: settlementRunId=${settlementRun.id}, sellerId=$outOfRangeSellerId",
				outOfRangeSellerId,
			)
		}
	}

	private fun fail(errorType: SettlementConfirmationErrorType, message: String, sellerId: Long? = null): Nothing =
		throw SettlementConfirmationException(errorType, message, sellerId)

	private companion object {
		val logger = LoggerFactory.getLogger(ConfirmSellerSettlementsTasklet::class.java)
	}
}
