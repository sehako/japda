package io.github.sehako.japda.batch.settlement.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SettlementDateRange(
	val startInclusive: Instant,
	val endExclusive: Instant,
) {
	companion object {
		private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")

		fun from(settlementDate: LocalDate): SettlementDateRange = SettlementDateRange(
			startInclusive = settlementDate.atStartOfDay(SEOUL_ZONE).toInstant(),
			endExclusive = settlementDate.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant(),
		)
	}
}
