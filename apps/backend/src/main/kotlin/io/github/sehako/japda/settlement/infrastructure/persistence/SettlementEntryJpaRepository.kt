package io.github.sehako.japda.settlement.infrastructure.persistence

import io.github.sehako.japda.settlement.domain.model.SettlementEntry
import org.springframework.data.jpa.repository.JpaRepository

interface SettlementEntryJpaRepository : JpaRepository<SettlementEntry, Long> {
	fun findByPaymentId(paymentId: Long): SettlementEntry?
}
