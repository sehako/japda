package io.github.sehako.japda.batch.settlement.application.tasklet

import io.github.sehako.japda.batch.settlement.exception.SettlementConfirmationErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementConfirmationException
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
			executeConfirmation(settlementRunId)
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

	private fun executeConfirmation(settlementRunId: Long): RepeatStatus {
		val settlementRun = settlementRunRepository.findByIdForUpdate(settlementRunId)
			?: fail(SettlementConfirmationErrorType.RUN_NOT_FOUND, "SettlementRun을 찾을 수 없습니다: settlementRunId=$settlementRunId")
		sellerSettlementRepository.lockDetails(settlementRunId)
		sellerSettlementRepository.lockSavedResults(settlementRunId)
		val detailAggregate = sellerSettlementRepository.aggregateDetails(settlementRunId)
		validateDetailAggregate(settlementRun, detailAggregate)
		validateRecipientsAndRanges(settlementRun)

		when (settlementRun.status) {
			SettlementRunStatus.COLLECTED -> confirm(settlementRun)
			SettlementRunStatus.CONFIRMED,
			SettlementRunStatus.COMPLETED,
			-> validateConfirmed(settlementRun)
			SettlementRunStatus.COLLECTING -> fail(
				SettlementConfirmationErrorType.INVALID_RUN_STATUS,
				"확정할 수 없는 SettlementRun 상태입니다: settlementRunId=$settlementRunId, status=${settlementRun.status}",
			)
		}
		return RepeatStatus.FINISHED
	}

	private fun confirm(settlementRun: SettlementRunSnapshot) {
		if (sellerSettlementRepository.countBySettlementRunId(settlementRun.id) != 0L) {
			fail(SettlementConfirmationErrorType.EXISTING_RESULT, "COLLECTED 실행에 기존 판매자별 정산 결과가 있습니다: settlementRunId=${settlementRun.id}")
		}
		val confirmedAt = Instant.now(clock)
		sellerSettlementRepository.insertAggregated(settlementRun.id, settlementRun.platformFeeRateBps, confirmedAt)
		validateSavedDetails(settlementRun)
		validateSavedAggregate(settlementRun)
		validateSavedFormula(settlementRun)
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
		validateSavedDetails(settlementRun, SettlementConfirmationErrorType.CONFIRMED_RESULT_MISMATCH)
		validateSavedAggregate(settlementRun, SettlementConfirmationErrorType.CONFIRMED_RESULT_MISMATCH)
		validateSavedFormula(settlementRun, SettlementConfirmationErrorType.CONFIRMED_RESULT_MISMATCH)
	}

	private fun validateSavedDetails(
		settlementRun: SettlementRunSnapshot,
		errorType: SettlementConfirmationErrorType = SettlementConfirmationErrorType.SAVED_RESULT_DETAIL_MISMATCH,
	) {
		val sellerId = sellerSettlementRepository.findConfirmedResultMismatchSellerId(
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
		val recipientMismatchSellerId = sellerSettlementRepository.findRecipientMismatchSellerId(settlementRun.id)
		if (recipientMismatchSellerId != null) {
			fail(
				SettlementConfirmationErrorType.RECIPIENT_MISMATCH,
				"한 판매자에 여러 지급 대상이 존재합니다: settlementRunId=${settlementRun.id}, sellerId=$recipientMismatchSellerId",
				recipientMismatchSellerId,
			)
		}
		val outOfRangeSellerId = sellerSettlementRepository.findAmountOutOfRangeSellerId(
			settlementRun.id,
			settlementRun.platformFeeRateBps,
		)
		if (outOfRangeSellerId != null) {
			fail(
				SettlementConfirmationErrorType.AMOUNT_OUT_OF_RANGE,
				"판매자별 계산 금액이 BIGINT 범위를 벗어납니다: settlementRunId=${settlementRun.id}, sellerId=$outOfRangeSellerId",
				outOfRangeSellerId,
			)
		}
	}

	private fun validateSavedAggregate(
		settlementRun: SettlementRunSnapshot,
		errorType: SettlementConfirmationErrorType = SettlementConfirmationErrorType.SAVED_RESULT_AGGREGATE_MISMATCH,
	) {
		val aggregate = sellerSettlementRepository.aggregateSavedResults(settlementRun.id)
		if (
			aggregate.itemCount.compareTo(BigDecimal.valueOf(settlementRun.collectedCount)) != 0 ||
			aggregate.totalAmount.compareTo(BigDecimal.valueOf(settlementRun.collectedAmount)) != 0
		) {
			fail(errorType, "판매자별 저장 결과의 전체 집계가 수집 집계와 다릅니다: settlementRunId=${settlementRun.id}")
		}
	}

	private fun validateSavedFormula(
		settlementRun: SettlementRunSnapshot,
		errorType: SettlementConfirmationErrorType = SettlementConfirmationErrorType.SAVED_RESULT_FORMULA_MISMATCH,
	) {
		val sellerId = sellerSettlementRepository.findSavedFormulaMismatchSellerId(
			settlementRun.id,
			settlementRun.platformFeeRateBps,
		)
		if (sellerId != null) {
			fail(errorType, "판매자별 저장 결과의 계산식이 다릅니다: settlementRunId=${settlementRun.id}, sellerId=$sellerId", sellerId)
		}
	}

	private fun fail(errorType: SettlementConfirmationErrorType, message: String, sellerId: Long? = null): Nothing =
		throw SettlementConfirmationException(errorType, message, sellerId)

	private companion object {
		val logger = LoggerFactory.getLogger(ConfirmSellerSettlementsTasklet::class.java)
	}
}
