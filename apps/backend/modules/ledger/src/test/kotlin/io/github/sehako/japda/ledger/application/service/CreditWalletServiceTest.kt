package io.github.sehako.japda.ledger.application.service

import io.github.sehako.japda.ledger.application.dto.CreditWalletCommand
import io.github.sehako.japda.ledger.domain.model.LedgerDirection
import io.github.sehako.japda.ledger.domain.model.LedgerEntrySnapshot
import io.github.sehako.japda.ledger.domain.model.LedgerSourceType
import io.github.sehako.japda.ledger.domain.model.WalletSnapshot
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import io.github.sehako.japda.ledger.domain.repository.WalletRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("지갑 입금 서비스")
class CreditWalletServiceTest {
	private val walletRepository = MemoryWalletRepository()
	private val ledgerEntryRepository = MemoryLedgerEntryRepository(walletRepository)
	private val service = CreditWalletService(walletRepository, ledgerEntryRepository, Clock.fixed(NOW, ZoneOffset.UTC))

	@Test
	@DisplayName("처음 입금하면 사용자 지갑과 원장을 생성한다")
	fun 처음_입금하면_사용자_지갑과_원장을_생성한다() {
		val result = service.credit(command(userId = 7L, amount = 1_500L, sourceId = 21L))

		assertEquals(1_500L, result.balanceAfter)
		assertFalse(result.alreadyApplied)
		assertEquals(WalletSnapshot(result.walletId, 7L, 1_500L), walletRepository.findByUserIdForUpdate(7L))
		assertEquals(
			LedgerEntrySnapshot(1L, result.walletId, 7L, LedgerDirection.CREDIT, 1_500L, 1_500L, LedgerSourceType.SELLER_SETTLEMENT, 21L),
			ledgerEntryRepository.findBySource(LedgerSourceType.SELLER_SETTLEMENT, 21L),
		)
	}

	@Test
	@DisplayName("기존 지갑에 입금하면 잔액을 더한 결과를 원장에 기록한다")
	fun 기존_지갑에_입금하면_잔액을_더한_결과를_원장에_기록한다() {
		walletRepository.wallets[7L] = WalletSnapshot(3L, 7L, 800L)

		val result = service.credit(command(userId = 7L, amount = 200L, sourceId = 22L))

		assertEquals(1_000L, result.balanceAfter)
		assertEquals(1_000L, ledgerEntryRepository.findBySource(LedgerSourceType.SELLER_SETTLEMENT, 22L)?.balanceAfter)
	}

	@Test
	@DisplayName("같은 원인의 동일 입금은 잔액을 다시 변경하지 않는다")
	fun 같은_원인의_동일_입금은_잔액을_다시_변경하지_않는다() {
		service.credit(command(userId = 7L, amount = 1_500L, sourceId = 21L))

		val result = service.credit(command(userId = 7L, amount = 1_500L, sourceId = 21L))

		assertEquals(1_500L, walletRepository.findByUserIdForUpdate(7L)?.balance)
		assertEquals(1, ledgerEntryRepository.entries.size)
		assertTrue(result.alreadyApplied)
	}

	@Test
	@DisplayName("같은 원인의 사용자나 방향 또는 금액이 다르면 실패한다")
	fun 같은_원인의_사용자나_방향_또는_금액이_다르면_실패한다() {
		walletRepository.wallets[7L] = WalletSnapshot(1L, 7L, 1_500L)
		ledgerEntryRepository.entries[LedgerSourceType.SELLER_SETTLEMENT to 21L] =
			LedgerEntrySnapshot(1L, 1L, 7L, LedgerDirection.DEBIT, 1_500L, 0L, LedgerSourceType.SELLER_SETTLEMENT, 21L)

		assertThrows<LedgerSourceMismatchException> {
			service.credit(command(userId = 7L, amount = 1_500L, sourceId = 21L))
		}
	}

	@Test
	@DisplayName("잔액 합계가 BIGINT 범위를 넘으면 실패한다")
	fun 잔액_합계가_BIGINT_범위를_넘으면_실패한다() {
		walletRepository.wallets[7L] = WalletSnapshot(1L, 7L, Long.MAX_VALUE)

		assertThrows<WalletBalanceOverflowException> {
			service.credit(command(userId = 7L, amount = 1L, sourceId = 21L))
		}
		assertEquals(Long.MAX_VALUE, walletRepository.findByUserIdForUpdate(7L)?.balance)
		assertTrue(ledgerEntryRepository.entries.isEmpty())
	}

	@Test
	@DisplayName("양수가 아닌 입력은 거부한다")
	fun 양수가_아닌_입력은_거부한다() {
		listOf(
			command(userId = 0L, amount = 1L, sourceId = 1L),
			command(userId = 1L, amount = 0L, sourceId = 1L),
			command(userId = 1L, amount = 1L, sourceId = 0L),
		).forEach { invalid -> assertThrows<IllegalArgumentException> { service.credit(invalid) } }
	}

	private fun command(userId: Long, amount: Long, sourceId: Long) =
		CreditWalletCommand(userId, amount, LedgerSourceType.SELLER_SETTLEMENT, sourceId)

	private class MemoryWalletRepository : WalletRepository {
		val wallets = mutableMapOf<Long, WalletSnapshot>()

		override fun createIfAbsent(userId: Long, now: Instant) {
			wallets.putIfAbsent(userId, WalletSnapshot((wallets.size + 1).toLong(), userId, 0L))
		}

		override fun findByUserIdForUpdate(userId: Long): WalletSnapshot? = wallets[userId]

		override fun updateBalance(walletId: Long, balance: Long, now: Instant) {
			val wallet = wallets.values.single { it.id == walletId }
			wallets[wallet.userId] = wallet.copy(balance = balance)
		}
	}

	private class MemoryLedgerEntryRepository(
		private val walletRepository: MemoryWalletRepository,
	) : LedgerEntryRepository {
		val entries = mutableMapOf<Pair<LedgerSourceType, Long>, LedgerEntrySnapshot>()

		override fun findBySource(sourceType: LedgerSourceType, sourceId: Long): LedgerEntrySnapshot? =
			entries[sourceType to sourceId]

		override fun create(
			walletId: Long,
			direction: LedgerDirection,
			amount: Long,
			balanceAfter: Long,
			sourceType: LedgerSourceType,
			sourceId: Long,
			now: Instant,
		) {
			val wallet = walletRepository.wallets.values.single { it.id == walletId }
			entries[sourceType to sourceId] = LedgerEntrySnapshot(
				(entries.size + 1).toLong(), walletId, wallet.userId, direction, amount, balanceAfter, sourceType, sourceId,
			)
		}
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-16T00:00:00Z")
	}
}
