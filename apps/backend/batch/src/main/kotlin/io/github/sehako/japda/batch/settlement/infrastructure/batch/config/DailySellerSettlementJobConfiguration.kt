package io.github.sehako.japda.batch.settlement.infrastructure.batch.config

import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.application.tasklet.CompleteSettlementCollectionTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.CompleteSettlementRunTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.ConfirmSellerSettlementsTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.PrepareSettlementRunTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.PrepareSettlementRunTasklet.Companion.SETTLEMENT_RUN_ID_CONTEXT_KEY
import io.github.sehako.japda.batch.settlement.application.validation.SettlementRunCreditValidator
import io.github.sehako.japda.batch.settlement.application.writer.SellerWalletCreditWriter
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.IdBounds
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.IdPartitionPlanPreparationTasklet
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.IdPartitionPlanService
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.PartitionPlanType
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.StoredIdPartitioner
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.StoredIdPartitioner.Companion.END_EXCLUSIVE_KEY
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.StoredIdPartitioner.Companion.END_INCLUSIVE_KEY
import io.github.sehako.japda.batch.settlement.infrastructure.batch.partition.StoredIdPartitioner.Companion.START_INCLUSIVE_KEY
import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SellerSettlementIdKeysetReader
import io.github.sehako.japda.batch.settlement.infrastructure.batch.reader.SettlementPaymentKeysetReader
import io.github.sehako.japda.batch.settlement.infrastructure.batch.validation.DailySellerSettlementJobParametersValidator
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
import io.github.sehako.japda.ledger.application.service.CreditWalletService
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import io.github.sehako.japda.ledger.infrastructure.config.LedgerConfiguration
import java.time.Clock
import java.time.LocalDate
import javax.sql.DataSource
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.ExecutionContextPromotionListener
import org.springframework.batch.core.partition.Partitioner
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@Import(LedgerConfiguration::class)
@EnableConfigurationProperties(DailySellerSettlementBatchProperties::class)
class DailySellerSettlementJobConfiguration(private val properties: DailySellerSettlementBatchProperties) {
	@Bean
	fun dailySellerSettlementJobParametersValidator(clock: Clock) = DailySellerSettlementJobParametersValidator(clock)

	@Bean
	fun settlementClock(): Clock = Clock.systemUTC()

	@Bean
	fun idPartitionPlanService() = IdPartitionPlanService()

	@Bean
	fun prepareSettlementRunTasklet(settlementRunRepository: SettlementRunJdbcRepository, clock: Clock) =
		PrepareSettlementRunTasklet(settlementRunRepository, clock)

	@Bean
	fun completeSettlementCollectionTasklet(settlementRunRepository: SettlementRunJdbcRepository, clock: Clock) =
		CompleteSettlementCollectionTasklet(settlementRunRepository, clock)

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
	): Step = StepBuilder(PREPARE_RUN_STEP_NAME, jobRepository)
		.tasklet(prepareSettlementRunTasklet, transactionManager)
		.listener(settlementRunIdPromotionListener)
		.allowStartIfComplete(true)
		.build()

	@Bean
	@StepScope
	fun prepareCollectionPartitionPlanTasklet(
		dataSource: DataSource,
		planService: IdPartitionPlanService,
		@Value("#{jobParameters['settlementDate']}") settlementDateParameter: String,
	): Tasklet {
		val range = SettlementDateRange.from(LocalDate.parse(settlementDateParameter))
		val jdbcTemplate = JdbcTemplate(dataSource)
		return IdPartitionPlanPreparationTasklet(planService, PartitionPlanType.COLLECTION, properties.collectionPartitionCount) {
			jdbcTemplate.queryForObject(
				COLLECTION_BOUNDS_SQL,
				{ resultSet, _ -> IdBounds(resultSet.getNullableLong("min_id"), resultSet.getNullableLong("max_id")) },
				LocalDate.parse(settlementDateParameter),
			)
		}
	}

	@Bean
	@StepScope
	fun prepareCreditPartitionPlanTasklet(
		dataSource: DataSource,
		planService: IdPartitionPlanService,
		@Value("#{jobExecutionContext['settlementRunId']}") settlementRunId: Long,
	): Tasklet {
		val jdbcTemplate = JdbcTemplate(dataSource)
		return IdPartitionPlanPreparationTasklet(planService, PartitionPlanType.CREDIT, properties.creditPartitionCount) {
			jdbcTemplate.queryForObject(
				CREDIT_BOUNDS_SQL,
				{ resultSet, _ -> IdBounds(resultSet.getNullableLong("min_id"), resultSet.getNullableLong("max_id")) },
				settlementRunId,
			)
		}
	}

	@Bean
	fun prepareCollectionPartitionPlanStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		@Qualifier("prepareCollectionPartitionPlanTasklet") tasklet: Tasklet,
	): Step = StepBuilder(PREPARE_COLLECTION_PLAN_STEP_NAME, jobRepository).tasklet(tasklet, transactionManager).build()

	@Bean
	fun prepareCreditPartitionPlanStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		@Qualifier("prepareCreditPartitionPlanTasklet") tasklet: Tasklet,
	): Step = StepBuilder(PREPARE_CREDIT_PLAN_STEP_NAME, jobRepository).tasklet(tasklet, transactionManager).build()

	@Bean
	@StepScope
	fun collectionPartitioner(
		planService: IdPartitionPlanService,
		@Value("#{stepExecution.jobExecution.executionContext}") jobExecutionContext: ExecutionContext,
	): Partitioner = StoredIdPartitioner(jobExecutionContext, PartitionPlanType.COLLECTION, planService)

	@Bean
	@StepScope
	fun creditPartitioner(
		planService: IdPartitionPlanService,
		@Value("#{stepExecution.jobExecution.executionContext}") jobExecutionContext: ExecutionContext,
	): Partitioner = StoredIdPartitioner(jobExecutionContext, PartitionPlanType.CREDIT, planService)

	@Bean(destroyMethod = "shutdown")
	fun settlementPartitionTaskExecutor() = ThreadPoolTaskExecutor().apply {
		corePoolSize = properties.workerCount
		maxPoolSize = properties.workerCount
		queueCapacity = maxOf(properties.collectionPartitionCount, properties.creditPartitionCount)
		setThreadNamePrefix("settlement-partition-")
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(EXECUTOR_AWAIT_TERMINATION_SECONDS)
		setStrictEarlyShutdown(true)
	}

	@Bean
	@StepScope
	fun settlementPaymentReader(
		dataSource: DataSource,
		@Value("#{jobParameters['settlementDate']}") settlementDateParameter: String,
		@Value("#{stepExecutionContext['$START_INCLUSIVE_KEY']}") partitionStartInclusive: Long,
		@Value("#{stepExecutionContext['$END_EXCLUSIVE_KEY']}") partitionEndExclusive: Long,
		@Value("#{stepExecutionContext['$END_INCLUSIVE_KEY']}") partitionEndInclusive: Boolean,
	): SettlementPaymentKeysetReader {
		val dateRange = SettlementDateRange.from(LocalDate.parse(settlementDateParameter))
		return SettlementPaymentKeysetReader(dataSource, dateRange, properties.pageSize, properties.fetchSize, partitionStartInclusive, partitionEndExclusive, partitionEndInclusive)
	}

	@Bean
	@StepScope
	fun settlementEntryCollectionWriter(): ItemWriter<SettlementPaymentProjection> = ItemWriter { }

	@Bean
	fun collectSettlementDetailsWorkerStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		settlementPaymentReader: ItemStreamReader<SettlementPaymentProjection>,
		settlementEntryCollectionWriter: ItemWriter<SettlementPaymentProjection>,
	): Step = StepBuilder(COLLECTION_WORKER_STEP_NAME, jobRepository)
		.chunk<SettlementPaymentProjection, SettlementPaymentProjection>(properties.chunkSize)
		.transactionManager(transactionManager)
		.reader(settlementPaymentReader)
		.stream(settlementPaymentReader)
		.writer(settlementEntryCollectionWriter)
		.build()

	@Bean
	fun collectSettlementDetailsManagerStep(
		jobRepository: JobRepository,
		@Qualifier("collectSettlementDetailsWorkerStep") workerStep: Step,
		@Qualifier("collectionPartitioner") partitioner: Partitioner,
		settlementPartitionTaskExecutor: ThreadPoolTaskExecutor,
	): Step = StepBuilder(COLLECTION_MANAGER_STEP_NAME, jobRepository)
		.partitioner(COLLECTION_WORKER_STEP_NAME, partitioner)
		.step(workerStep)
		.gridSize(properties.collectionPartitionCount)
		.taskExecutor(settlementPartitionTaskExecutor)
		.build()

	@Bean
	fun completeSettlementCollectionStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		completeSettlementCollectionTasklet: CompleteSettlementCollectionTasklet,
	): Step = StepBuilder(COMPLETE_COLLECTION_STEP_NAME, jobRepository).tasklet(completeSettlementCollectionTasklet, transactionManager).build()

	@Bean
	fun confirmSellerSettlementsStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		confirmSellerSettlementsTasklet: ConfirmSellerSettlementsTasklet,
	): Step = StepBuilder(CONFIRM_STEP_NAME, jobRepository).tasklet(confirmSellerSettlementsTasklet, transactionManager).build()

	@Bean
	@StepScope
	fun sellerSettlementIdReader(
		dataSource: DataSource,
		@Value("#{jobExecutionContext['settlementRunId']}") settlementRunId: Long,
		@Value("#{stepExecutionContext['$START_INCLUSIVE_KEY']}") partitionStartInclusive: Long,
		@Value("#{stepExecutionContext['$END_EXCLUSIVE_KEY']}") partitionEndExclusive: Long,
		@Value("#{stepExecutionContext['$END_INCLUSIVE_KEY']}") partitionEndInclusive: Boolean,
	): SellerSettlementIdKeysetReader = SellerSettlementIdKeysetReader(dataSource, settlementRunId, properties.pageSize, properties.fetchSize, partitionStartInclusive, partitionEndExclusive, partitionEndInclusive)

	@Bean
	@StepScope
	fun sellerWalletCreditWriter(
		@Value("#{jobExecutionContext['settlementRunId']}") settlementRunId: Long,
		settlementRunCreditValidator: SettlementRunCreditValidator,
		sellerSettlementRepository: SellerSettlementJdbcRepository,
		creditWalletService: CreditWalletService,
		ledgerEntryRepository: LedgerEntryRepository,
		clock: Clock,
	): ItemWriter<Long> = SellerWalletCreditWriter(
		settlementRunId,
		settlementRunCreditValidator.validate(settlementRunId),
		sellerSettlementRepository,
		creditWalletService,
		ledgerEntryRepository,
		clock,
	)

	@Bean
	fun creditSellerWalletsWorkerStep(
		jobRepository: JobRepository,
		transactionManager: PlatformTransactionManager,
		sellerSettlementIdReader: ItemStreamReader<Long>,
		sellerWalletCreditWriter: ItemWriter<Long>,
	): Step = StepBuilder(CREDIT_WORKER_STEP_NAME, jobRepository)
		.chunk<Long, Long>(properties.chunkSize)
		.transactionManager(transactionManager)
		.reader(sellerSettlementIdReader)
		.stream(sellerSettlementIdReader)
		.writer(sellerWalletCreditWriter)
		.build()

	@Bean
	fun creditSellerWalletsManagerStep(
		jobRepository: JobRepository,
		@Qualifier("creditSellerWalletsWorkerStep") workerStep: Step,
		@Qualifier("creditPartitioner") partitioner: Partitioner,
		settlementPartitionTaskExecutor: ThreadPoolTaskExecutor,
	): Step = StepBuilder(CREDIT_MANAGER_STEP_NAME, jobRepository)
		.partitioner(CREDIT_WORKER_STEP_NAME, partitioner)
		.step(workerStep)
		.gridSize(properties.creditPartitionCount)
		.taskExecutor(settlementPartitionTaskExecutor)
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
		@Qualifier("prepareSettlementRunStep") prepareRunStep: Step,
		@Qualifier("prepareCollectionPartitionPlanStep") prepareCollectionPlanStep: Step,
		@Qualifier("collectSettlementDetailsManagerStep") collectionManagerStep: Step,
		@Qualifier("completeSettlementCollectionStep") completeCollectionStep: Step,
		@Qualifier("confirmSellerSettlementsStep") confirmStep: Step,
		@Qualifier("prepareCreditPartitionPlanStep") prepareCreditPlanStep: Step,
		@Qualifier("creditSellerWalletsManagerStep") creditManagerStep: Step,
		@Qualifier("completeSettlementRunStep") completeRunStep: Step,
	): Job = JobBuilder(JOB_NAME, jobRepository)
		.validator(dailySellerSettlementJobParametersValidator)
		.start(prepareRunStep)
		.next(prepareCollectionPlanStep)
		.next(collectionManagerStep)
		.next(completeCollectionStep)
		.next(confirmStep)
		.next(prepareCreditPlanStep)
		.next(creditManagerStep)
		.next(completeRunStep)
		.build()

	private fun java.sql.ResultSet.getNullableLong(columnName: String): Long? = (getObject(columnName) as? Number)?.toLong()

	private companion object {
		const val JOB_NAME = "dailySellerSettlementJob"
		const val PREPARE_RUN_STEP_NAME = "prepareSettlementRunStep"
		const val PREPARE_COLLECTION_PLAN_STEP_NAME = "prepareCollectionPartitionPlanStep"
		const val COLLECTION_MANAGER_STEP_NAME = "collectSettlementDetailsManagerStep"
		const val COLLECTION_WORKER_STEP_NAME = "collectSettlementDetailsWorkerStep"
		const val COMPLETE_COLLECTION_STEP_NAME = "completeSettlementCollectionStep"
		const val CONFIRM_STEP_NAME = "confirmSellerSettlementsStep"
		const val PREPARE_CREDIT_PLAN_STEP_NAME = "prepareCreditPartitionPlanStep"
		const val CREDIT_MANAGER_STEP_NAME = "creditSellerWalletsManagerStep"
		const val CREDIT_WORKER_STEP_NAME = "creditSellerWalletsWorkerStep"
		const val COMPLETE_RUN_STEP_NAME = "completeSettlementRunStep"
		const val EXECUTOR_AWAIT_TERMINATION_SECONDS = 60

		val COLLECTION_BOUNDS_SQL = """
			SELECT MIN(id) AS min_id, MAX(id) AS max_id
			FROM settlement_entries
			WHERE settlement_date = ?
		""".trimIndent()
		val CREDIT_BOUNDS_SQL = """
			SELECT MIN(id) AS min_id, MAX(id) AS max_id
			FROM seller_settlements
			WHERE settlement_run_id = ?
		""".trimIndent()
	}
}
