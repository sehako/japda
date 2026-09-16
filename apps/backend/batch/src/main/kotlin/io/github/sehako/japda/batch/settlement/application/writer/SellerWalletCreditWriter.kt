package io.github.sehako.japda.batch.settlement.application.writer

import io.github.sehako.japda.batch.settlement.exception.SettlementCreditErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementCreditException
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementSnapshot
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementStatus
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStatus
import io.github.sehako.japda.ledger.application.dto.CreditWalletCommand
import io.github.sehako.japda.ledger.application.service.CreditWalletService
import io.github.sehako.japda.ledger.domain.model.LedgerDirection
import io.github.sehako.japda.ledger.domain.model.LedgerEntrySnapshot
import io.github.sehako.japda.ledger.domain.model.LedgerSourceType
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import java.time.Clock
import java.time.Instant
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

class SellerWalletCreditWriter(
	private val settlementRunId: Long,
	private val settlementRunRepository: SettlementRunJdbcRepository,
	private val sellerSettlementRepository: SellerSettlementJdbcRepository,
	private val creditWalletService: CreditWalletService,
	private val ledgerEntryRepository: LedgerEntryRepository,
	private val clock: Clock,
) : ItemWriter<Long> {
	override fun write(chunk: Chunk<out Long>) {
		chunk.forEach(::credit)
	}

	private fun credit(sellerSettlementId: Long) {
		val settlement = sellerSettlementRepository.findByIdForUpdate(sellerSettlementId)
			?: fail(SettlementCreditErrorType.SELLER_SETTLEMENT_NOT_FOUND, "판매자별 정산을 찾을 수 없습니다: sellerSettlementId=$sellerSettlementId", sellerSettlementId)
		if (settlement.settlementRunId != settlementRunId) {
			fail(SettlementCreditErrorType.RUN_MISMATCH, "판매자별 정산의 실행 ID가 다릅니다: sellerSettlementId=$sellerSettlementId", sellerSettlementId, settlement.sellerId)
		}
		val run = settlementRunRepository.findByIdForUpdate(settlementRunId)
			?: fail(SettlementCreditErrorType.RUN_NOT_FOUND, "SettlementRun을 찾을 수 없습니다: settlementRunId=$settlementRunId")
		if (run.status !in setOf(SettlementRunStatus.CONFIRMED, SettlementRunStatus.COMPLETED)) {
			fail(SettlementCreditErrorType.INVALID_RUN_STATUS, "입금할 수 없는 SettlementRun 상태입니다: settlementRunId=$settlementRunId, status=${run.status}", sellerSettlementId, settlement.sellerId)
		}
		if (run.status == SettlementRunStatus.COMPLETED && settlement.status != SellerSettlementStatus.CREDITED) {
			fail(SettlementCreditErrorType.CREDIT_RESULT_MISMATCH, "완료된 실행에 입금되지 않은 판매자별 정산이 있습니다: sellerSettlementId=$sellerSettlementId", sellerSettlementId, settlement.sellerId)
		}
		when (settlement.status) {
			SellerSettlementStatus.CONFIRMED -> creditConfirmed(settlement)
			SellerSettlementStatus.CREDITED -> validateCredited(settlement)
		}
	}

	private fun creditConfirmed(settlement: SellerSettlementSnapshot) {
		val existing = ledgerEntryRepository.findBySource(LedgerSourceType.SELLER_SETTLEMENT, settlement.id)
		if (existing != null) {
			fail(SettlementCreditErrorType.UNEXPECTED_LEDGER_ENTRY, "입금 전 판매자별 정산에 이미 원장이 있습니다: sellerSettlementId=${settlement.id}", settlement.id, settlement.sellerId)
		}
		if (settlement.netAmount > 0) {
			creditWalletService.credit(
				CreditWalletCommand(
					userId = settlement.recipientUserId,
					amount = settlement.netAmount,
					sourceType = LedgerSourceType.SELLER_SETTLEMENT,
					sourceId = settlement.id,
				),
			)
		}
		try {
			sellerSettlementRepository.markCredited(settlement.id, Instant.now(clock))
		} catch (exception: RuntimeException) {
			throw SettlementCreditException(SettlementCreditErrorType.STATE_TRANSITION_FAILED, "판매자별 정산 입금 상태 전환에 실패했습니다: sellerSettlementId=${settlement.id}", settlement.id, settlement.sellerId, exception)
		}
	}

	private fun validateCredited(settlement: SellerSettlementSnapshot) {
		if (settlement.creditedAt == null) {
			fail(SettlementCreditErrorType.CREDIT_RESULT_MISMATCH, "입금 완료 시각이 없습니다: sellerSettlementId=${settlement.id}", settlement.id, settlement.sellerId)
		}
		val existing = ledgerEntryRepository.findBySource(LedgerSourceType.SELLER_SETTLEMENT, settlement.id)
		if (settlement.netAmount == 0L) {
			if (existing != null) failMismatch(settlement)
			return
		}
		if (existing == null || !existing.matches(settlement)) failMismatch(settlement)
	}

	private fun LedgerEntrySnapshot.matches(settlement: SellerSettlementSnapshot): Boolean =
		walletUserId == settlement.recipientUserId &&
			direction == LedgerDirection.CREDIT &&
			amount == settlement.netAmount &&
			sourceType == LedgerSourceType.SELLER_SETTLEMENT &&
			sourceId == settlement.id

	private fun failMismatch(settlement: SellerSettlementSnapshot): Nothing =
		fail(SettlementCreditErrorType.CREDIT_RESULT_MISMATCH, "판매자별 정산과 원장 결과가 다릅니다: sellerSettlementId=${settlement.id}", settlement.id, settlement.sellerId)

	private fun fail(
		errorType: SettlementCreditErrorType,
		message: String,
		sellerSettlementId: Long? = null,
		sellerId: Long? = null,
	): Nothing = throw SettlementCreditException(errorType, message, sellerSettlementId, sellerId)
}
