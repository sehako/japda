package io.github.sehako.japda.batch.settlement.domain.model

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName

@DisplayName("정산일 조회 구간")
class SettlementDateRangeTest {
	@Test
	@DisplayName("한국 정산일의 시작 이상 다음 날 시작 미만 구간을 계산한다")
	fun 한국_정산일의_시작_이상_다음_날_시작_미만_구간을_계산한다() {
		val range = SettlementDateRange.from(LocalDate.parse("2026-09-15"))

		assertEquals(Instant.parse("2026-09-14T15:00:00Z"), range.startInclusive)
		assertEquals(Instant.parse("2026-09-15T15:00:00Z"), range.endExclusive)
	}
}
