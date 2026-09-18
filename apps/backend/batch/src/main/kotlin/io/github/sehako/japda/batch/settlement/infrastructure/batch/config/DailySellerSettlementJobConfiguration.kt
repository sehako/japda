package io.github.sehako.japda.batch.settlement.infrastructure.batch.config

import io.github.sehako.japda.batch.settlement.application.dto.CreateSettlementDetailCommand
import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.application.processor.SettlementPaymentProcessor
import io.github.sehako.japda.batch.settlement.application.tasklet.CompleteSettlementCollectionTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.CompleteSettlementRunTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.ConfirmSellerSettlementsTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.PrepareSettlementRunTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.PrepareSettlementRunTasklet.Companion.SETTLEMENT_RUN_ID_CONTEXT_KEY
import io.github.sehako.japda.batch.settlement.application.writer.SellerWalletCreditWriter
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import io.github.sehako.japda.batch.settlement.infrastructure.batch.validation.DailySellerSettlementJobParametersValidator
import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SettlementPaymentKeysetReader
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
import io.github.sehako.japda.ledger.application.service.CreditWalletService
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import io.github.sehako.japda.ledger.infrastructure.config.LedgerConfiguration
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.ExecutionContextPromotionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader
import org.springframework.batch.infrastructure.item.database.Order
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@Import(LedgerConfiguration::class)
@EnableConfigurationProperties(DailySellerSettlementBatchProperties::class)
class DailySellerSettlementJobConfiguration(
	private val properties: DailySellerSettlementBatchProperties,
) {
	@Bean
	fun dailySellerSettlementJobParametersValidator(clock: Clock) =
		DailySellerSettlementJobParametersValidator(clock)

	@Bean
	fun settlementClock(): Clock = Clock.systemUTC()

	@Bean
	fun prepareSettlementRunTasklet(
		settlementRunRepository: SettlementRunJdbcRepository,
		clock: Clock,
	) = PrepareSettlementRunTasklet(settlementRunRepository, clock)

	@Bean
	fun completeSettlementCollectionTasklet(
		settlementRunRepository: SettlementRunJdbcRepository,
		clock: Clock,
	) = CompleteSettlementCollectionTasklet(settlementRunRepository, clock)

	@Bean
	fun confirmSellerSettlementsTasklet(
		settlementRunRepository: SettlementRunJdbcRepository,
		sellerSettlementRepository: SellerSettlementJdbcRepository,
		clock: Clock,
	) = ConfirmSellerSettlementsTasklet(settlementRunRepository, sellerSettlementRepository, clock)

	@Bean
	fun completeSettlementRunTasklet(
		settlementRunRepository: SettlementRunJdbcRepository,
		sellerSettlementRepository: SellerSettlementJdbcRepository,
		clock: Clock,
	) = CompleteSettlementRunTasklet(settlementRunRepository, sellerSettlementRepository, clock)

	@Bean
	fun settlementRunIdPromotionListener() = ExecutionContextPromotionListener().apply {
		setKeys(arrayOf(SETTLEMENT_RUN_ID_CONTEXT_KEY))
		setStrict(true)
	}

	@Bean
	fun prepareSettlementRunStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		prepareSettlementRunTasklet: PrepareSettlementRunTasklet,
		settlementRunIdPromotionListener: ExecutionContextPromotionListener,
	): Step = StepBuilder(PREPARE_STEP_NAME, jobRepository)
		.tasklet(prepareSettlementRunTasklet, transactionManager)
		.listener(settlementRunIdPromotionListener)
		.allowStartIfComplete(true)
		.build()

	@Bean
	@StepScope
	fun settlementPaymentReader(
		dataSource: DataSource,
		@Value("#{jobParameters['settlementDate']}") settlementDateParameter: String,
	): SettlementPaymentKeysetReader {
		val dateRange = SettlementDateRange.from(LocalDate.parse(settlementDateParameter))
		return SettlementPaymentKeysetReader(dataSource, dateRange, properties.pageSize, properties.fetchSize)
	}

	@Bean
	@StepScope
	fun settlementPaymentProcessor(
		@Value("#{jobExecutionContext['settlementRunId']}") settlementRunId: Long,
	): ItemProcessor<SettlementPaymentProjection, CreateSettlementDetailCommand> =
		SettlementPaymentProcessor(settlementRunId)

	@Bean
	fun settlementDetailWriter(dataSource: DataSource): JdbcBatchItemWriter<CreateSettlementDetailCommand> =
		JdbcBatchItemWriterBuilder<CreateSettlementDetailCommand>()
			.dataSource(dataSource)
			.sql(
				"""
				INSERT INTO settlement_details (
					settlement_run_id, payment_id, order_id, sale_id, seller_id, recipient_user_id,
					quantity, unit_price, gross_amount, payment_approved_at, created_at
				) VALUES (
					:settlementRunId, :paymentId, :orderId, :saleId, :sellerId, :recipientUserId,
					:quantity, :unitPrice, :grossAmount, :paymentApprovedAt, CURRENT_TIMESTAMP
				)
				""".trimIndent(),
			)
			.beanMapped()
			.build()

	@Bean
	fun collectSettlementDetailsStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		settlementPaymentReader: ItemStreamReader<SettlementPaymentProjection>,
		settlementPaymentProcessor: ItemProcessor<SettlementPaymentProjection, CreateSettlementDetailCommand>,
		settlementDetailWriter: JdbcBatchItemWriter<CreateSettlementDetailCommand>,
	): Step = StepBuilder(COLLECT_STEP_NAME, jobRepository)
		.chunk<SettlementPaymentProjection, CreateSettlementDetailCommand>(properties.chunkSize)
		.transactionManager(transactionManager)
		.reader(settlementPaymentReader)
		.stream(settlementPaymentReader)
		.processor(settlementPaymentProcessor)
		.writer(settlementDetailWriter)
		.build()

	@Bean
	fun completeSettlementCollectionStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		completeSettlementCollectionTasklet: CompleteSettlementCollectionTasklet,
	): Step = StepBuilder(COMPLETE_STEP_NAME, jobRepository)
		.tasklet(completeSettlementCollectionTasklet, transactionManager)
		.build()

	@Bean
	fun confirmSellerSettlementsStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		confirmSellerSettlementsTasklet: ConfirmSellerSettlementsTasklet,
	): Step = StepBuilder(CONFIRM_STEP_NAME, jobRepository)
		.tasklet(confirmSellerSettlementsTasklet, transactionManager)
		.build()

	@Bean
	@StepScope
	fun sellerSettlementIdReader(
		dataSource: DataSource,
		@Value("#{jobExecutionContext['settlementRunId']}") settlementRunId: Long,
	): JdbcPagingItemReader<Long> {
		val queryProvider = PostgresPagingQueryProvider().apply {
			setSelectClause("id")
			setFromClause("seller_settlements")
			setWhereClause("settlement_run_id = :settlementRunId")
			setSortKeys(linkedMapOf("id" to Order.ASCENDING))
		}
		return JdbcPagingItemReaderBuilder<Long>()
			.name("sellerSettlementIdReader")
			.dataSource(dataSource)
			.queryProvider(queryProvider)
			.parameterValues(mapOf("settlementRunId" to settlementRunId))
			.pageSize(properties.pageSize)
			.fetchSize(properties.fetchSize)
			.saveState(true)
			.rowMapper { resultSet, _ -> resultSet.getLong("id") }
			.build()
	}

	@Bean
	@StepScope
	fun sellerWalletCreditWriter(
		@Value("#{jobExecutionContext['settlementRunId']}") settlementRunId: Long,
		settlementRunRepository: SettlementRunJdbcRepository,
		sellerSettlementRepository: SellerSettlementJdbcRepository,
		creditWalletService: CreditWalletService,
		ledgerEntryRepository: LedgerEntryRepository,
		clock: Clock,
	): ItemWriter<Long> = SellerWalletCreditWriter(
		settlementRunId,
		settlementRunRepository,
		sellerSettlementRepository,
		creditWalletService,
		ledgerEntryRepository,
		clock,
	)

	@Bean
	fun creditSellerWalletsStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		sellerSettlementIdReader: JdbcPagingItemReader<Long>,
		sellerWalletCreditWriter: ItemWriter<Long>,
	): Step = StepBuilder(CREDIT_STEP_NAME, jobRepository)
		.chunk<Long, Long>(properties.chunkSize)
		.transactionManager(transactionManager)
		.reader(sellerSettlementIdReader)
		.stream(sellerSettlementIdReader)
		.writer(sellerWalletCreditWriter)
		.allowStartIfComplete(true)
		.build()

	@Bean
	fun completeSettlementRunStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		completeSettlementRunTasklet: CompleteSettlementRunTasklet,
	): Step = StepBuilder(COMPLETE_RUN_STEP_NAME, jobRepository)
		.tasklet(completeSettlementRunTasklet, transactionManager)
		.allowStartIfComplete(true)
		.build()

	@Bean
	fun dailySellerSettlementJob(
		jobRepository: JobRepository,
		dailySellerSettlementJobParametersValidator: DailySellerSettlementJobParametersValidator,
		@Qualifier("prepareSettlementRunStep") prepareSettlementRunStep: Step,
		@Qualifier("collectSettlementDetailsStep") collectSettlementDetailsStep: Step,
		@Qualifier("completeSettlementCollectionStep") completeSettlementCollectionStep: Step,
		@Qualifier("confirmSellerSettlementsStep") confirmSellerSettlementsStep: Step,
		@Qualifier("creditSellerWalletsStep") creditSellerWalletsStep: Step,
		@Qualifier("completeSettlementRunStep") completeSettlementRunStep: Step,
	): Job = JobBuilder(JOB_NAME, jobRepository)
		.validator(dailySellerSettlementJobParametersValidator)
		.start(prepareSettlementRunStep)
		.next(collectSettlementDetailsStep)
		.next(completeSettlementCollectionStep)
		.next(confirmSellerSettlementsStep)
		.next(creditSellerWalletsStep)
		.next(completeSettlementRunStep)
		.build()

	private fun java.sql.ResultSet.getNullableLong(columnName: String): Long? =
		(getObject(columnName) as? Number)?.toLong()

	private fun java.sql.ResultSet.getNullableInt(columnName: String): Int? =
		(getObject(columnName) as? Number)?.toInt()

	private companion object {
		const val JOB_NAME = "dailySellerSettlementJob"
		const val PREPARE_STEP_NAME = "prepareSettlementRunStep"
		const val COLLECT_STEP_NAME = "collectSettlementDetailsStep"
		const val COMPLETE_STEP_NAME = "completeSettlementCollectionStep"
		const val CONFIRM_STEP_NAME = "confirmSellerSettlementsStep"
		const val CREDIT_STEP_NAME = "creditSellerWalletsStep"
		const val COMPLETE_RUN_STEP_NAME = "completeSettlementRunStep"
	}
}
