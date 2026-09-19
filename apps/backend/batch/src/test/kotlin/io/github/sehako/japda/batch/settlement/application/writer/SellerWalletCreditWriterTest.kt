package io.github.sehako.japda.batch.settlement.application.writer

import io.github.sehako.japda.batch.settlement.exception.SettlementCreditErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementCreditException
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementSnapshot
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementStatus
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunStatus
import io.github.sehako.japda.ledger.application.dto.CreditWalletCommand
import io.github.sehako.japda.ledger.application.dto.CreditWalletResult
import io.github.sehako.japda.ledger.application.service.CreditWalletService
import io.github.sehako.japda.ledger.application.service.LedgerSourceMismatchException
import io.github.sehako.japda.ledger.domain.model.LedgerDirection
import io.github.sehako.japda.ledger.domain.model.LedgerEntrySnapshot
import io.github.sehako.japda.ledger.domain.model.LedgerSourceType
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.batch.infrastructure.item.Chunk

@DisplayName("판매자 지갑 입금 Writer")
class SellerWalletCreditWriterTest {
	private lateinit var sellerSettlementRepository: SellerSettlementJdbcRepository
	private lateinit var creditWalletService: CreditWalletService
	private lateinit var ledgerEntryRepository: LedgerEntryRepository
	private lateinit var writer: SellerWalletCreditWriter

	@BeforeEach
	fun setUp() {
		sellerSettlementRepository = mock(SellerSettlementJdbcRepository::class.java)
		creditWalletService = mock(CreditWalletService::class.java)
		ledgerEntryRepository = mock(LedgerEntryRepository::class.java)
		writer = SellerWalletCreditWriter(
			settlementRunId = 10L,
			settlementRunStatus = SettlementRunStatus.CONFIRMED,
			sellerSettlementRepository = sellerSettlementRepository,
			creditWalletService = creditWalletService,
			ledgerEntryRepository = ledgerEntryRepository,
			clock = Clock.fixed(NOW, ZoneOffset.UTC),
		)
	}

	@Test
	@DisplayName("완료된 실행의 미입금 정산은 결과 불일치로 실패한다")
	fun 완료된_실행의_미입금_정산은_결과_불일치로_실패한다() {
		writer = SellerWalletCreditWriter(
			settlementRunId = 10L,
			settlementRunStatus = SettlementRunStatus.COMPLETED,
			sellerSettlementRepository = sellerSettlementRepository,
			creditWalletService = creditWalletService,
			ledgerEntryRepository = ledgerEntryRepository,
			clock = Clock.fixed(NOW, ZoneOffset.UTC),
		)
		stubConfirmedSettlement(id = 1L)

		val exception = assertFailsWith<SettlementCreditException> {
			writer.write(Chunk(listOf(1L)))
		}

		assertEquals(SettlementCreditErrorType.CREDIT_RESULT_MISMATCH, exception.errorType)
	}

	@Test
	@DisplayName("양수 신규 정산은 Writer에서 원장을 선행 조회하지 않는다")
	fun 양수_신규_정산은_Writer에서_원장을_선행_조회하지_않는다() {
		stubConfirmedSettlement(id = 1L)
		stubNewCredit(id = 1L)

		writer.write(Chunk(listOf(1L)))

		verify(ledgerEntryRepository, never()).findBySource(LedgerSourceType.SELLER_SETTLEMENT, 1L)
		verify(sellerSettlementRepository).markCredited(1L, NOW)
	}

	@Test
	@DisplayName("이미 적용된 양수 원장은 예상하지 못한 원장 오류로 실패한다")
	fun 이미_적용된_양수_원장은_예상하지_못한_원장_오류로_실패한다() {
		stubConfirmedSettlement(id = 1L)
		`when`(creditWalletService.credit(creditCommand(id = 1L))).thenReturn(
			CreditWalletResult(walletId = 20L, balanceAfter = 1_000L, alreadyApplied = true),
		)

		val exception = assertFailsWith<SettlementCreditException> {
			writer.write(Chunk(listOf(1L)))
		}

		assertEquals(SettlementCreditErrorType.UNEXPECTED_LEDGER_ENTRY, exception.errorType)
		verify(sellerSettlementRepository, never()).markCredited(1L, NOW)
	}

	@Test
	@DisplayName("원장 source 불일치는 예상하지 못한 원장 오류로 변환한다")
	fun 원장_source_불일치는_예상하지_못한_원장_오류로_변환한다() {
		stubConfirmedSettlement(id = 1L)
		`when`(creditWalletService.credit(creditCommand(id = 1L))).thenThrow(
			LedgerSourceMismatchException(LedgerSourceType.SELLER_SETTLEMENT, 1L),
		)

		val exception = assertFailsWith<SettlementCreditException> {
			writer.write(Chunk(listOf(1L)))
		}

		assertEquals(SettlementCreditErrorType.UNEXPECTED_LEDGER_ENTRY, exception.errorType)
		verify(sellerSettlementRepository, never()).markCredited(1L, NOW)
	}

	@Test
	@DisplayName("0원 신규 정산은 기존 원장 부재를 확인한다")
	fun 금액이_0인_신규_정산은_기존_원장_부재를_확인한다() {
		stubConfirmedSettlement(id = 1L, netAmount = 0L)

		writer.write(Chunk(listOf(1L)))

		verify(ledgerEntryRepository).findBySource(LedgerSourceType.SELLER_SETTLEMENT, 1L)
		verify(creditWalletService, never()).credit(creditCommand(id = 1L, amount = 0L))
		verify(sellerSettlementRepository).markCredited(1L, NOW)
	}

	@Test
	@DisplayName("입금 완료 정산은 기존 원장을 다시 검산한다")
	fun 입금_완료_정산은_기존_원장을_다시_검산한다() {
		`when`(sellerSettlementRepository.findByIdForUpdate(1L)).thenReturn(
			SellerSettlementSnapshot(
				id = 1L,
				settlementRunId = 10L,
				sellerId = 101L,
				recipientUserId = 201L,
				netAmount = 1_000L,
				status = SellerSettlementStatus.CREDITED,
				creditedAt = NOW,
			),
		)
		`when`(ledgerEntryRepository.findBySource(LedgerSourceType.SELLER_SETTLEMENT, 1L)).thenReturn(
			LedgerEntrySnapshot(
				id = 30L,
				walletId = 20L,
				walletUserId = 201L,
				direction = LedgerDirection.CREDIT,
				amount = 1_000L,
				balanceAfter = 1_000L,
				sourceType = LedgerSourceType.SELLER_SETTLEMENT,
				sourceId = 1L,
			),
		)

		writer.write(Chunk(listOf(1L)))

		verify(ledgerEntryRepository).findBySource(LedgerSourceType.SELLER_SETTLEMENT, 1L)
		verify(creditWalletService, never()).credit(creditCommand(id = 1L))
		verify(sellerSettlementRepository, never()).markCredited(1L, NOW)
	}

	private fun stubNewCredit(id: Long) {
		`when`(creditWalletService.credit(creditCommand(id))).thenReturn(newCreditResult())
	}

	private fun creditCommand(id: Long, amount: Long = 1_000L) = CreditWalletCommand(
		userId = id + 200L,
		amount = amount,
		sourceType = LedgerSourceType.SELLER_SETTLEMENT,
		sourceId = id,
	)

	private fun stubConfirmedSettlement(id: Long, netAmount: Long = 1_000L) {
		`when`(sellerSettlementRepository.findByIdForUpdate(id)).thenReturn(
			SellerSettlementSnapshot(
				id = id,
				settlementRunId = 10L,
				sellerId = id + 100L,
				recipientUserId = id + 200L,
				netAmount = netAmount,
				status = SellerSettlementStatus.CONFIRMED,
				creditedAt = null,
			),
		)
	}

	private fun newCreditResult() = CreditWalletResult(
		walletId = 20L,
		balanceAfter = 1_000L,
		alreadyApplied = false,
	)

	companion object {
		private val NOW = Instant.parse("2026-09-17T01:00:00Z")
	}
}
