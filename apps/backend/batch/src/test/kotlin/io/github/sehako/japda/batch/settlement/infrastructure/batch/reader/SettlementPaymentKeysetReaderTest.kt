package io.github.sehako.japda.batch.settlement.infrastructure.batch.reader

import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamException

@DisplayName("판매자 일일 정산 tuple keyset reader")
class SettlementPaymentKeysetReaderTest {
	@Test
	@DisplayName("재시작 cursor가 일부만 저장되면 reader를 열지 않는다")
	fun 재시작_cursor가_일부만_저장되면_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putString("settlementPaymentKeysetReader.lastApprovedAt", "2026-09-14T15:00:00Z")
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("재시작 cursor의 승인 시각을 복원할 수 없으면 reader를 열지 않는다")
	fun 재시작_cursor의_승인_시각을_복원할_수_없으면_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putString("settlementPaymentKeysetReader.lastApprovedAt", "손상된-시각")
			putLong("settlementPaymentKeysetReader.lastPaymentId", 1L)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	private fun reader() = SettlementPaymentKeysetReader(
		mock(DataSource::class.java),
		SettlementDateRange.from(LocalDate.of(2026, 9, 15)),
		pageSize = 100,
		fetchSize = 100,
	)
}
