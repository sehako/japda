package io.github.sehako.japda.settlement.infrastructure.persistence

import io.github.sehako.japda.settlement.domain.model.SettlementEntry
import io.github.sehako.japda.settlement.domain.repository.SettlementEntryRepository
import org.springframework.stereotype.Repository

@Repository
class SettlementEntryRepositoryImpl(
	private val settlementEntryJpaRepository: SettlementEntryJpaRepository,
) : SettlementEntryRepository {
	override fun findByPaymentId(paymentId: Long): SettlementEntry? = settlementEntryJpaRepository.findByPaymentId(paymentId)

	override fun save(entry: SettlementEntry): SettlementEntry = settlementEntryJpaRepository.saveAndFlush(entry)
}
