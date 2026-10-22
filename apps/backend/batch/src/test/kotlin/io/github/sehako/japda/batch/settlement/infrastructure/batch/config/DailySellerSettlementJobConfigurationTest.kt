package io.github.sehako.japda.batch.settlement.infrastructure.batch.config

import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SellerSettlementIdKeysetReader
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.item.ChunkOrientedStep
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.DirectFieldAccessor
import org.springframework.transaction.PlatformTransactionManager

@DisplayName("판매자 일일 정산 Job 처리 단위 구성")
class DailySellerSettlementJobConfigurationTest {
	private val properties = DailySellerSettlementBatchProperties(
		chunkSize = 11,
		pageSize = 12,
		fetchSize = 13,
		workerCount = 3,
		creditPartitionCount = 9,
	)
	private val configuration = DailySellerSettlementJobConfiguration(properties)

	@Test
	@DisplayName("판매자별 정산 reader에 page와 fetch 설정을 적용한다")
	fun 판매자별_정산_reader_page_fetch_설정을_적용한다() {
		val dataSource = mock(DataSource::class.java)
		val sellerSettlementReader = configuration.sellerSettlementIdReader(dataSource, 1L, 1L, 10L, false)

		assertEquals(12, sellerSettlementReader.pageSize)
		assertEquals(13, sellerSettlementReader.fetchSize)
	}

	@Test
	@DisplayName("credit manager가 사용할 bounded worker executor를 구성한다")
	fun credit_manager_bounded_worker_executor를_구성한다() {
		val executor = configuration.settlementPartitionTaskExecutor()
		executor.initialize()
		try {
			assertEquals(3, executor.corePoolSize)
			assertEquals(3, executor.maxPoolSize)
			assertEquals(9, executor.queueCapacity)
		} finally {
			executor.destroy()
		}
	}

	@Test
	@DisplayName("지갑 입금 step에 chunk 설정을 적용한다")
	@Suppress("UNCHECKED_CAST")
	fun 지갑_입금_step_chunk_설정을_적용한다() {
		val jobRepository = mock(JobRepository::class.java)
		val transactionManager = mock(PlatformTransactionManager::class.java)
		val settlementReader = mock(SellerSettlementIdKeysetReader::class.java)
		val walletWriter = mock(ItemWriter::class.java) as ItemWriter<Long>

		val steps = listOf(
			configuration.creditSellerWalletsWorkerStep(
				jobRepository,
				transactionManager,
				settlementReader,
				walletWriter,
			),
		)

		steps.forEach { step ->
			assertEquals(11, DirectFieldAccessor(step as ChunkOrientedStep<*, *>).getPropertyValue("chunkSize"))
		}
	}
}
