package io.github.sehako.japda.batch.settlement.application.validation

import io.github.sehako.japda.batch.settlement.exception.SettlementCreditErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementCreditException
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunSnapshot
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStatus
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@DisplayName("지갑 입금 worker 실행 검증기")
class SettlementRunCreditValidatorTest {
	private val repository = mock(SettlementRunJdbcRepository::class.java)
	private val validator = SettlementRunCreditValidator(repository)

	@Test
	@DisplayName("확정된 실행은 잠금 없는 snapshot 상태를 반환한다")
	fun 확정된_실행은_잠금_없는_snapshot_상태를_반환한다() {
		`when`(repository.findById(10L)).thenReturn(run(SettlementRunStatus.CONFIRMED))

		assertEquals(SettlementRunStatus.CONFIRMED, validator.validate(10L))
	}

	@Test
	@DisplayName("수집 중인 실행은 입금 worker 시작을 거절한다")
	fun 수집_중인_실행은_입금_worker_시작을_거절한다() {
		`when`(repository.findById(10L)).thenReturn(run(SettlementRunStatus.COLLECTING))

		val exception = assertFailsWith<SettlementCreditException> {
			validator.validate(10L)
		}

		assertEquals(SettlementCreditErrorType.INVALID_RUN_STATUS, exception.errorType)
	}

	private fun run(status: SettlementRunStatus) = SettlementRunSnapshot(
		id = 10L,
		settlementDate = LocalDate.of(2026, 9, 16),
		platformFeeRateBps = 1_000,
		status = status,
		collectedCount = 0,
		collectedAmount = 0,
		collectionCompletedAt = null,
		confirmationCompletedAt = null,
		completedAt = null,
	)
}
