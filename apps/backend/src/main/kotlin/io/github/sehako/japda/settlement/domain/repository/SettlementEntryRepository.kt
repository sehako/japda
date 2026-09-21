package io.github.sehako.japda.settlement.domain.repository

import io.github.sehako.japda.settlement.domain.model.SettlementEntry

interface SettlementEntryRepository {
	fun findByPaymentId(paymentId: Long): SettlementEntry?

	fun save(entry: SettlementEntry): SettlementEntry
}
