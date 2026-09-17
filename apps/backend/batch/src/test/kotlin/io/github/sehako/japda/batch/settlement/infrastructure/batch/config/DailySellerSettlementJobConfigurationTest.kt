package io.github.sehako.japda.batch.settlement.infrastructure.batch.config

import io.github.sehako.japda.batch.settlement.application.dto.CreateSettlementDetailCommand
import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.item.ChunkOrientedStep
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader
import org.springframework.beans.DirectFieldAccessor
import org.springframework.transaction.PlatformTransactionManager

@DisplayName("판매자 일일 정산 Job 처리 단위 구성")
class DailySellerSettlementJobConfigurationTest {
	private val properties = DailySellerSettlementBatchProperties(
		chunkSize = 11,
		pageSize = 12,
		fetchSize = 13,
	)
	private val configuration = DailySellerSettlementJobConfiguration(properties)

	@Test
	@DisplayName("결제와 판매자별 정산 reader에 page와 fetch 설정을 적용한다")
	fun 결제와_판매자별_정산_reader_page_fetch_설정을_적용한다() {
		val dataSource = mock(DataSource::class.java)
		val readers = listOf(
			configuration.settlementPaymentReader(dataSource, "2026-09-15"),
			configuration.sellerSettlementIdReader(dataSource, 1L),
		)

		readers.forEach { reader ->
			assertEquals(12, reader.pageSize)
			assertEquals(13, DirectFieldAccessor(reader).getPropertyValue("fetchSize"))
		}
	}

	@Test
	@DisplayName("수집과 지갑 입금 step에 chunk 설정을 적용한다")
	@Suppress("UNCHECKED_CAST")
	fun 수집과_지갑_입금_step_chunk_설정을_적용한다() {
		val jobRepository = mock(JobRepository::class.java)
		val transactionManager = mock(PlatformTransactionManager::class.java)
		val paymentReader = mock(JdbcPagingItemReader::class.java) as JdbcPagingItemReader<SettlementPaymentProjection>
		val processor = mock(ItemProcessor::class.java) as ItemProcessor<SettlementPaymentProjection, CreateSettlementDetailCommand>
		val detailWriter = mock(JdbcBatchItemWriter::class.java) as JdbcBatchItemWriter<CreateSettlementDetailCommand>
		val settlementReader = mock(JdbcPagingItemReader::class.java) as JdbcPagingItemReader<Long>
		val walletWriter = mock(ItemWriter::class.java) as ItemWriter<Long>

		val steps = listOf(
			configuration.collectSettlementDetailsStep(
				jobRepository,
				transactionManager,
				paymentReader,
				processor,
				detailWriter,
			),
			configuration.creditSellerWalletsStep(
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
