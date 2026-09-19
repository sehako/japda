package io.github.sehako.japda.batch.settlement.infrastructure.batch.reader

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

@DisplayName("판매자별 정산 ID keyset reader")
class SellerSettlementIdKeysetReaderTest {
	@Test
	@DisplayName("신규 worker의 첫 checkpoint 전에는 cursor 없이 시작한다")
	fun 신규_worker의_첫_checkpoint_전에는_cursor_없이_시작한다() {
		val reader = reader()
		val executionContext = ExecutionContext()

		reader.open(executionContext)
		reader.update(executionContext)

		assertFalse(executionContext.containsKey("sellerSettlementIdKeysetReader.checkpointed"))
	}

	@Test
	@DisplayName("checkpoint sentinel만 남은 손상된 context는 reader를 열지 않는다")
	fun checkpoint_sentinel만_남은_손상된_context는_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putInt("sellerSettlementIdKeysetReader.checkpointed", 1)
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
			putString("sellerSettlementIdKeysetReader.checkpointed", "true")
			putLong("sellerSettlementIdKeysetReader.lastSellerSettlementId", 150L)
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
			putInt("sellerSettlementIdKeysetReader.checkpointed", 1)
			putString("sellerSettlementIdKeysetReader.lastSellerSettlementId", "150")
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
			putLong("sellerSettlementIdKeysetReader.lastSellerSettlementId", 150L)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	@Test
	@DisplayName("조회는 실행 ID와 파티션 범위 및 cursor를 함께 적용한다")
	fun 조회는_실행_ID와_파티션_범위_및_cursor를_함께_적용한다() {
		val query = SellerSettlementIdKeysetQuery.create(
			settlementRunId = 10L,
			pageSize = 100,
			partitionStartInclusive = 100L,
			partitionEndExclusive = 200L,
			partitionEndInclusive = false,
			lastSellerSettlementId = 150L,
		)

		assertEquals(10L, query.parameters["settlementRunId"])
		assertEquals(100L, query.parameters["partitionStartInclusive"])
		assertEquals(200L, query.parameters["partitionEndExclusive"])
		assertEquals(150L, query.parameters["lastSellerSettlementId"])
		assertTrue(query.sql.contains("id >= :partitionStartInclusive"))
		assertTrue(query.sql.contains("id < :partitionEndExclusive"))
		assertTrue(query.sql.contains("id > :lastSellerSettlementId"))
		assertTrue(query.sql.contains("ORDER BY id ASC"))
	}

	@Test
	@DisplayName("재시작 cursor가 파티션 범위를 벗어나면 reader를 열지 않는다")
	fun 재시작_cursor가_파티션_범위를_벗어나면_reader를_열지_않는다() {
		val reader = reader()
		val executionContext = ExecutionContext().apply {
			putInt("sellerSettlementIdKeysetReader.checkpointed", 1)
			putLong("sellerSettlementIdKeysetReader.lastSellerSettlementId", 200L)
		}

		assertFailsWith<ItemStreamException> {
			reader.open(executionContext)
		}
	}

	private fun reader() = SellerSettlementIdKeysetReader(
		dataSource = mock(DataSource::class.java),
		settlementRunId = 10L,
		pageSize = 100,
		fetchSize = 100,
		partitionStartInclusive = 100L,
		partitionEndExclusive = 200L,
		partitionEndInclusive = false,
	)
}
