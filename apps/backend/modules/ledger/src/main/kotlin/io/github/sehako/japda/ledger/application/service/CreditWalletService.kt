package io.github.sehako.japda.ledger.application.service

import io.github.sehako.japda.ledger.application.dto.CreditWalletCommand
import io.github.sehako.japda.ledger.application.dto.CreditWalletResult
import io.github.sehako.japda.ledger.domain.model.LedgerDirection
import io.github.sehako.japda.ledger.domain.model.LedgerEntrySnapshot
import io.github.sehako.japda.ledger.domain.model.LedgerSourceType
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import io.github.sehako.japda.ledger.domain.repository.WalletRepository
import java.time.Clock
import org.springframework.transaction.annotation.Transactional

open class CreditWalletService(
	private val walletRepository: WalletRepository,
	private val ledgerEntryRepository: LedgerEntryRepository,
	private val clock: Clock,
) {
	@Transactional
	open fun credit(command: CreditWalletCommand): CreditWalletResult {
		validate(command)
		ledgerEntryRepository.findBySource(command.sourceType, command.sourceId)?.let {
			return validateExisting(it, command)
		}

		val now = clock.instant()
		walletRepository.createIfAbsent(command.userId, now)
		val wallet = walletRepository.findByUserIdForUpdate(command.userId)
			?: throw WalletNotFoundException(command.userId)

		ledgerEntryRepository.findBySource(command.sourceType, command.sourceId)?.let {
			return validateExisting(it, command)
		}

		val balanceAfter = try {
			Math.addExact(wallet.balance, command.amount)
		} catch (exception: ArithmeticException) {
			throw WalletBalanceOverflowException(wallet.id, exception)
		}
		walletRepository.updateBalance(wallet.id, balanceAfter, now)
		ledgerEntryRepository.create(
			walletId = wallet.id,
			direction = LedgerDirection.CREDIT,
			amount = command.amount,
			balanceAfter = balanceAfter,
			sourceType = command.sourceType,
			sourceId = command.sourceId,
			now = now,
		)
		return CreditWalletResult(wallet.id, balanceAfter, alreadyApplied = false)
	}

	private fun validate(command: CreditWalletCommand) {
		require(command.userId > 0) { "사용자 식별자는 양수여야 합니다." }
		require(command.amount > 0) { "입금액은 양수여야 합니다." }
		require(command.sourceId > 0) { "원인 식별자는 양수여야 합니다." }
		require(command.sourceType == LedgerSourceType.SELLER_SETTLEMENT) { "지원하지 않는 원인 종류입니다." }
	}

	private fun validateExisting(entry: LedgerEntrySnapshot, command: CreditWalletCommand): CreditWalletResult {
		if (
			entry.walletUserId != command.userId ||
			entry.direction != LedgerDirection.CREDIT ||
			entry.amount != command.amount
		) {
			throw LedgerSourceMismatchException(command.sourceType, command.sourceId)
		}
		return CreditWalletResult(entry.walletId, entry.balanceAfter, alreadyApplied = true)
	}
}

class WalletNotFoundException(userId: Long) :
	IllegalStateException("생성 또는 잠금 조회할 지갑을 찾을 수 없습니다. userId=$userId")

class WalletBalanceOverflowException(walletId: Long, cause: ArithmeticException) :
	IllegalStateException("지갑 잔액이 BIGINT 범위를 벗어납니다. walletId=$walletId", cause)

class LedgerSourceMismatchException(sourceType: LedgerSourceType, sourceId: Long) :
	IllegalStateException("기존 원장이 입금 요청과 일치하지 않습니다. sourceType=$sourceType, sourceId=$sourceId")
