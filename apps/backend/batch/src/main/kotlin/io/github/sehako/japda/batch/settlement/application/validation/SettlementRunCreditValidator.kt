package io.github.sehako.japda.batch.settlement.application.validation

import io.github.sehako.japda.batch.settlement.exception.SettlementCreditErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementCreditException
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStatus
import org.springframework.stereotype.Component

@Component
class SettlementRunCreditValidator(
	private val settlementRunRepository: SettlementRunJdbcRepository,
) {
	fun validate(settlementRunId: Long): SettlementRunStatus {
		val run = settlementRunRepository.findById(settlementRunId)
			?: throw SettlementCreditException(
				SettlementCreditErrorType.RUN_NOT_FOUND,
				"SettlementRun을 찾을 수 없습니다: settlementRunId=$settlementRunId",
			)
		if (run.status !in CREDITABLE_STATUSES) {
			throw SettlementCreditException(
				SettlementCreditErrorType.INVALID_RUN_STATUS,
				"입금할 수 없는 SettlementRun 상태입니다: settlementRunId=$settlementRunId, status=${run.status}",
			)
		}
		return run.status
	}

	private companion object {
		val CREDITABLE_STATUSES = setOf(SettlementRunStatus.CONFIRMED, SettlementRunStatus.COMPLETED)
	}
}
