package io.github.sehako.japda.batch.settlement.infrastructure.batch.config

import io.github.sehako.japda.batch.settlement.application.dto.CreateSettlementDetailCommand
import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.application.processor.SettlementPaymentProcessor
import io.github.sehako.japda.batch.settlement.application.tasklet.CompleteSettlementCollectionTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.ConfirmSellerSettlementsTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.PrepareSettlementRunTasklet
import io.github.sehako.japda.batch.settlement.application.tasklet.PrepareSettlementRunTasklet.Companion.SETTLEMENT_RUN_ID_CONTEXT_KEY
import io.github.sehako.japda.batch.settlement.domain.model.SettlementDateRange
import io.github.sehako.japda.batch.settlement.infrastructure.batch.validation.DailySellerSettlementJobParametersValidator
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SettlementRunJdbcRepository
import io.github.sehako.japda.batch.settlement.infrastructure.persistence.SellerSettlementJdbcRepository
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
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader
import org.springframework.batch.infrastructure.item.database.Order
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
class DailySellerSettlementJobConfiguration {
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
	): JdbcPagingItemReader<SettlementPaymentProjection> {
		val dateRange = SettlementDateRange.from(LocalDate.parse(settlementDateParameter))
		val queryProvider = PostgresPagingQueryProvider().apply {
			setSelectClause("*")
			setFromClause(PAYMENT_PROJECTION_FROM_CLAUSE)
			setWhereClause(
				"payment_status = :approvedStatus AND payment_approved_at >= :startInclusive AND payment_approved_at < :endExclusive",
			)
			setSortKeys(
				linkedMapOf(
					"payment_approved_at" to Order.ASCENDING,
					"payment_id" to Order.ASCENDING,
				),
			)
		}
		return JdbcPagingItemReaderBuilder<SettlementPaymentProjection>()
			.name("settlementPaymentReader")
			.dataSource(dataSource)
			.queryProvider(queryProvider)
			.parameterValues(
				mapOf(
					"approvedStatus" to APPROVED_PAYMENT_STATUS,
					"startInclusive" to dateRange.startInclusive.atOffset(ZoneOffset.UTC),
					"endExclusive" to dateRange.endExclusive.atOffset(ZoneOffset.UTC),
				),
			)
			.pageSize(CHUNK_SIZE)
			.fetchSize(CHUNK_SIZE)
			.saveState(true)
			.rowMapper { resultSet, _ ->
				SettlementPaymentProjection(
					paymentId = resultSet.getLong("payment_id"),
					requestedAmount = resultSet.getLong("requested_amount"),
					paymentApprovedAt = resultSet.getTimestamp("payment_approved_at").toInstant(),
					orderId = resultSet.getNullableLong("order_id"),
					orderStatus = resultSet.getString("order_status"),
					saleId = resultSet.getNullableLong("sale_id"),
					sellerId = resultSet.getNullableLong("seller_id"),
					recipientUserId = resultSet.getNullableLong("recipient_user_id"),
					quantity = resultSet.getNullableInt("quantity"),
					unitPrice = resultSet.getNullableLong("unit_price"),
					totalPrice = resultSet.getNullableLong("total_price"),
				)
			}
			.build()
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
		settlementPaymentReader: JdbcPagingItemReader<SettlementPaymentProjection>,
		settlementPaymentProcessor: ItemProcessor<SettlementPaymentProjection, CreateSettlementDetailCommand>,
		settlementDetailWriter: JdbcBatchItemWriter<CreateSettlementDetailCommand>,
	): Step = StepBuilder(COLLECT_STEP_NAME, jobRepository)
		.chunk<SettlementPaymentProjection, CreateSettlementDetailCommand>(CHUNK_SIZE)
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
	fun dailySellerSettlementJob(
		jobRepository: JobRepository,
		dailySellerSettlementJobParametersValidator: DailySellerSettlementJobParametersValidator,
		@Qualifier("prepareSettlementRunStep") prepareSettlementRunStep: Step,
		@Qualifier("collectSettlementDetailsStep") collectSettlementDetailsStep: Step,
		@Qualifier("completeSettlementCollectionStep") completeSettlementCollectionStep: Step,
		@Qualifier("confirmSellerSettlementsStep") confirmSellerSettlementsStep: Step,
	): Job = JobBuilder(JOB_NAME, jobRepository)
		.validator(dailySellerSettlementJobParametersValidator)
		.start(prepareSettlementRunStep)
		.next(collectSettlementDetailsStep)
		.next(completeSettlementCollectionStep)
		.next(confirmSellerSettlementsStep)
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
		const val CHUNK_SIZE = 100
		const val APPROVED_PAYMENT_STATUS = "APPROVED"

		val PAYMENT_PROJECTION_FROM_CLAUSE =
			"""
			(
				SELECT p.id AS payment_id,
				       p.requested_amount AS requested_amount,
				       p.approved_at AS payment_approved_at,
				       p.status AS payment_status,
				       o.id AS order_id,
				       o.status AS order_status,
				       s.id AS sale_id,
				       s.seller_id AS seller_id,
				       spi.user_id AS recipient_user_id,
				       o.quantity AS quantity,
				       o.unit_price AS unit_price,
				       o.total_price AS total_price
				FROM payments p
				LEFT JOIN orders o ON o.id = p.order_id
				LEFT JOIN sales s ON s.id = o.sale_id
				LEFT JOIN seller_principal_identities spi ON spi.seller_id = s.seller_id
			) settlement_payment
			""".trimIndent()
	}
}
