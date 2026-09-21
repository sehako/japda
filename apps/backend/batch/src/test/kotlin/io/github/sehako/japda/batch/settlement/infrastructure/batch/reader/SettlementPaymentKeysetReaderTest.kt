package io.github.sehako.japda.batch.settlement.infrastructure.batch.reader

import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamException

@DisplayName("판매자 일일 정산 tuple keyset reader")
class SettlementPaymentKeysetReaderTest {
	@Test
	@DisplayName("신규 worker의 첫 checkpoint 전에는 cursor 없이 시작한다")
	fun 신규_worker의_첫_checkpoint_전에는_cursor_없이_시작한다() {
		val reader = reader()
		val executionContext = ExecutionContext()

		reader.open(executionContext)
		reader.update(executionContext)

		assertFalse(executionContext.containsKey("settlementPaymentKeysetReader.checkpointed"))
	}

	@Test
	@DisplayName("checkpoint sentinel만 남은 손상된 context는 reader를 열지 않는다")
	fun checkpoint_sentinel만_남은_손상된_context는_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putInt("settlementPaymentKeysetReader.checkpointed", 1)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("checkpoint sentinel 타입이 잘못되면 reader를 열지 않는다")
	fun checkpoint_sentinel_타입이_잘못되면_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putString("settlementPaymentKeysetReader.checkpointed", "true")
			putLong("settlementPaymentKeysetReader.lastEntryId", 150L)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("checkpoint cursor 타입이 잘못되면 reader를 열지 않는다")
	fun checkpoint_cursor_타입이_잘못되면_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putInt("settlementPaymentKeysetReader.checkpointed", 1)
			putString("settlementPaymentKeysetReader.lastEntryId", "150")
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("sentinel 없이 cursor만 남은 손상된 context는 reader를 열지 않는다")
	fun sentinel_없이_cursor만_남은_손상된_context는_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putLong("settlementPaymentKeysetReader.lastEntryId", 150L)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("재시작 cursor가 파티션 시작보다 작으면 reader를 열지 않는다")
	fun 재시작_cursor가_파티션_시작보다_작으면_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putInt("settlementPaymentKeysetReader.checkpointed", 1)
			putLong("settlementPaymentKeysetReader.lastEntryId", 99L)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("재시작 cursor가 파티션 상한 이상이면 reader를 열지 않는다")
	fun 재시작_cursor가_파티션_상한_이상이면_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putInt("settlementPaymentKeysetReader.checkpointed", 1)
			putLong("settlementPaymentKeysetReader.lastEntryId", 200L)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("정산 원천 조회는 날짜와 파티션 ID 범위 및 ID cursor를 함께 적용한다")
	fun 정산_원천_조회는_날짜와_파티션_ID_범위_및_ID_cursor를_함께_적용한다() {
		val query = SettlementPaymentKeysetQuery.create(
			dateRange = SettlementDateRange.from(LocalDate.of(2026, 9, 15)),
			pageSize = 100,
			partitionStartInclusive = 100L,
			partitionEndExclusive = 200L,
			partitionEndInclusive = false,
			lastPaymentId = 150L,
		)

		assertEquals(100L, query.parameters["partitionStartInclusive"])
		assertEquals(200L, query.parameters["partitionEndExclusive"])
		assertEquals(150L, query.parameters["lastPaymentId"])
		assertTrue(query.sql.contains("FROM settlement_entries se"))
		assertTrue(query.sql.contains("se.id >= :partitionStartInclusive"))
		assertTrue(query.sql.contains("se.id < :partitionEndExclusive"))
		assertTrue(query.sql.contains("se.id > :lastPaymentId"))
		assertTrue(query.sql.contains("ORDER BY se.id ASC"))
		assertFalse(query.sql.contains("JOIN orders"))
		assertFalse(query.sql.contains("JOIN sales"))
		assertFalse(query.sql.contains("seller_principal_identities"))
	}

	@Test
	@DisplayName("Long 최대 ID 파티션은 포함 상한으로 조회한다")
	fun Long_최대_ID_파티션은_포함_상한으로_조회한다() {
		val query = SettlementPaymentKeysetQuery.create(
			dateRange = SettlementDateRange.from(LocalDate.of(2026, 9, 15)),
			pageSize = 100,
			partitionStartInclusive = Long.MAX_VALUE,
			partitionEndExclusive = Long.MAX_VALUE,
			partitionEndInclusive = true,
			lastPaymentId = null,
		)

		assertEquals(Long.MAX_VALUE, query.parameters["partitionEndExclusive"])
		assertTrue(query.sql.contains("se.id <= :partitionEndExclusive"))
	}

	private fun reader() = SettlementPaymentKeysetReader(
		mock(DataSource::class.java),
		SettlementDateRange.from(LocalDate.of(2026, 9, 15)),
		pageSize = 100,
		fetchSize = 100,
		partitionStartInclusive = 100L,
		partitionEndExclusive = 200L,
		partitionEndInclusive = false,
	)
}
