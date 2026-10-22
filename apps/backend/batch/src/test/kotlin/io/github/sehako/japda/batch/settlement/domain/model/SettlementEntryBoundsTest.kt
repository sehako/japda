package io.github.sehako.japda.batch.settlement.domain.model

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.infrastructure.item.ExecutionContext

@DisplayName("정산 원천 ID 경계")
class SettlementEntryBoundsTest {
	@Test
	@DisplayName("저장된 경계가 없으면 조회한 경계를 Job ExecutionContext에 고정한다")
	fun 저장된_경계가_없으면_조회한_경계를_고정한다() {
		val context = ExecutionContext()

		val bounds = SettlementEntryBounds.fixIfAbsent(context) { SettlementEntryBounds(10L, 19L) }

		assertEquals(SettlementEntryBounds(10L, 19L), bounds)
		assertEquals(10L, context.getLong("settlementEntry.minId"))
		assertEquals(19L, context.getLong("settlementEntry.maxId"))
	}

	@Test
	@DisplayName("저장된 경계가 있으면 다시 조회하지 않고 재사용한다")
	fun 저장된_경계가_있으면_다시_조회하지_않고_재사용한다() {
		val context = ExecutionContext().apply {
			putLong("settlementEntry.minId", 10L)
			putLong("settlementEntry.maxId", 19L)
		}
		var queried = false

		val bounds = SettlementEntryBounds.fixIfAbsent(context) {
			queried = true
			SettlementEntryBounds(1L, 100L)
		}

		assertEquals(false, queried)
		assertEquals(SettlementEntryBounds(10L, 19L), bounds)
	}

	@Test
	@DisplayName("대상이 없으면 경계를 저장하지 않고 경계 없음으로 복원한다")
	fun 대상이_없으면_경계를_저장하지_않고_경계_없음으로_복원한다() {
		val context = ExecutionContext()

		val bounds = SettlementEntryBounds.fixIfAbsent(context) { SettlementEntryBounds(null, null) }

		assertEquals(SettlementEntryBounds(null, null), bounds)
		assertEquals(SettlementEntryBounds(null, null), SettlementEntryBounds.from(context))
	}

	@Test
	@DisplayName("경계 key가 일부만 저장되어 있으면 실패한다")
	fun 경계_key가_일부만_저장되어_있으면_실패한다() {
		val context = ExecutionContext().apply { putLong("settlementEntry.minId", 10L) }

		assertFailsWith<IllegalStateException> {
			SettlementEntryBounds.fixIfAbsent(context) { SettlementEntryBounds(1L, 100L) }
		}
	}
}
