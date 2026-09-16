package io.github.sehako.japda.batch.performance

import com.zaxxer.hikari.HikariDataSource
import io.github.sehako.japda.batch.BatchApplication
import io.github.sehako.japda.batch.performance.database.IterationDatabase
import io.github.sehako.japda.batch.performance.database.PerformancePostgresFixture
import io.github.sehako.japda.batch.performance.dataset.ExpectedSettlement
import io.github.sehako.japda.batch.performance.dataset.SyntheticDatasetFactory
import io.github.sehako.japda.batch.performance.dataset.SyntheticDatasetInserter
import io.github.sehako.japda.batch.performance.measurement.DockerStatsPostgresResourceProbe
import io.github.sehako.japda.batch.performance.measurement.ResourceSampler
import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.IterationKind
import io.github.sehako.japda.batch.performance.measurement.model.IterationMeasurement
import io.github.sehako.japda.batch.performance.measurement.model.ResourceAvailability
import io.github.sehako.japda.batch.performance.measurement.model.ResourceSample
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import io.github.sehako.japda.batch.performance.report.PerformanceReportWriter
import io.github.sehako.japda.batch.performance.report.PerformanceStatistics
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenario
import io.github.sehako.japda.batch.performance.scenario.PerformanceScenarioLoader
import io.github.sehako.japda.batch.performance.validation.BatchCounterValidator
import io.github.sehako.japda.batch.performance.validation.ExpectedSettlementValues
import io.github.sehako.japda.batch.performance.validation.SettlementResultValidator
import io.github.sehako.japda.batch.performance.validation.ValidationReport
import io.github.sehako.japda.batch.settlement.infrastructure.batch.config.DailySellerSettlementBatchProperties
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory

@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("판매자 일일 정산 Job 성능 테스트")
class DailySellerSettlementJobPerformanceTest {
	@Test
	@DisplayName("독립 PostgreSQL 환경에서 실제 Job을 반복 측정하고 검산 결과를 기록한다")
	fun 독립_PostgreSQL_환경에서_실제_Job을_반복_측정하고_검산_결과를_기록한다() {
		val runId = requiredSystemProperty("japda.performance.run-id")
		val resultDirectory = Path.of(requiredSystemProperty("japda.performance.result-directory"))
		val migrationDirectory = Path.of(requiredSystemProperty("rootMigrationDirectory"))
		val scenario = PerformanceScenarioLoader().load()
		val reportWriter = PerformanceReportWriter(resultDirectory)
		val iterations = mutableListOf<IterationMeasurement>()
		val resources = mutableListOf<ResourceSample>()
		var validation = ValidationReport(true, emptyMap(), emptyList())
		val failures = mutableListOf<String>()
		val scenarioValues = baseScenarioValues(runId, scenario)

		try {
			PerformancePostgresFixture(migrationDirectory).use { fixture ->
				fixture.start()
				scenarioValues["postgres.image"] = fixture.runtime.imageName
				val totalIterations = scenario.warmupIterations + scenario.measurementIterations
				repeat(totalIterations) { zeroBasedIndex ->
					val index = zeroBasedIndex + 1
					val kind = if (zeroBasedIndex < scenario.warmupIterations) {
						IterationKind.WARMUP
					} else {
						IterationKind.MEASUREMENT
					}
					val outcome = runCatching {
						runIteration(index, kind, scenario, fixture, scenarioValues)
					}.getOrElse { exception ->
						failedOutcome(index, kind, exception)
					}
					iterations += outcome.measurement
					resources += outcome.resources
					validation = validation.merge(outcome.validation.prefixed(index))
					outcome.failure?.let { failures += "iteration $index: $it" }
					if (outcome.failure != null) return@repeat
				}
			}
		} catch (exception: Throwable) {
			failures += exception.failureMessage()
			validation = validation.merge(
				ValidationReport(false, emptyMap(), listOf(exception.failureMessage())),
			)
		} finally {
			reportWriter.write(scenarioValues, iterations, resources, validation)
			printSummary(runId, resultDirectory, scenario, scenarioValues, iterations, resources, validation)
		}

		assertTrue(
			failures.isEmpty() && validation.success && iterations.size == scenario.warmupIterations + scenario.measurementIterations,
			(failures + validation.failures).distinct().joinToString("\n"),
		)
	}

	private fun runIteration(
		index: Int,
		kind: IterationKind,
		scenario: PerformanceScenario,
		fixture: PerformancePostgresFixture,
		scenarioValues: MutableMap<String, String>,
	): IterationOutcome {
		val database = fixture.createIteration()
		val calculationStartedAt = System.nanoTime()
		val dataset = SyntheticDatasetFactory.create(scenario)
		val calculationMillis = elapsedMillis(calculationStartedAt)
		val preparationJdbc = JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
		val insertStartedAt = System.nanoTime()
		SyntheticDatasetInserter().insertAndVerify(preparationJdbc, dataset)
		val insertMillis = elapsedMillis(insertStartedAt)

		val contextStartedAt = System.nanoTime()
		val context = startContext(database)
		val contextStartMillis = elapsedMillis(contextStartedAt)
		context.use {
			val jdbcTemplate = context.getBean(JdbcTemplate::class.java)
			captureEffectiveEnvironment(context, jdbcTemplate, scenarioValues)
			val execution = executeJob(index, scenario, fixture, context)
			val steps = execution.jobExecution?.stepMeasurements().orEmpty()
			var iterationValidation = validateExecution(
				execution.jobExecution,
				execution.wallDurationMillis,
				steps,
				dataset.expectedSettlement,
				context,
				jdbcTemplate,
			)
			val status = execution.status
			if (status != ExecutionStatus.COMPLETED) {
				iterationValidation = iterationValidation.merge(
					ValidationReport(false, emptyMap(), listOf(execution.failure ?: "Job 실행 실패")),
				)
			}
			return IterationOutcome(
				measurement = IterationMeasurement(
					index = index,
					kind = kind,
					status = status,
					migrationMillis = database.migrationDuration.toMillis(),
					dataCalculationMillis = calculationMillis,
					dataInsertMillis = insertMillis,
					contextStartMillis = contextStartMillis,
					jobDurationMillis = execution.wallDurationMillis,
					processedOrderCount = dataset.expectedSettlement.detailCount,
					steps = steps,
					validationSuccess = iterationValidation.success,
				),
				resources = execution.resources,
				validation = iterationValidation,
				failure = execution.failure ?: iterationValidation.failures.firstOrNull(),
			)
		}
	}

	private fun executeJob(
		iterationIndex: Int,
		scenario: PerformanceScenario,
		fixture: PerformancePostgresFixture,
		context: ConfigurableApplicationContext,
	): JobExecutionOutcome {
		val jobOperator = context.getBean(JobOperator::class.java)
		val job = context.getBean("dailySellerSettlementJob", Job::class.java)
		val parameters = JobParametersBuilder()
			.addString("settlementDate", scenario.job.settlementDate.toString(), true)
			.addLong("platformFeeRateBps", scenario.job.platformFeeRateBps.toLong(), false)
			.toJobParameters()
		val probe = DockerStatsPostgresResourceProbe(
			DockerClientFactory.lazyClient(),
			fixture.runtime.containerId,
		)
		val sampler = ResourceSampler(iterationIndex, scenario.resourceSamplingInterval, probe)
		val executor = Executors.newSingleThreadExecutor { runnable ->
			Thread(runnable, "settlement-performance-job").apply { isDaemon = true }
		}
		val startedAt = System.nanoTime()
		sampler.start()
		val future = executor.submit<JobExecution> { jobOperator.start(job, parameters) }
		return try {
			val jobExecution = future.get(scenario.job.timeout.toMillis(), TimeUnit.MILLISECONDS)
			val status = if (jobExecution.status == BatchStatus.COMPLETED) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED
			JobExecutionOutcome(
				status,
				elapsedMillis(startedAt),
				jobExecution,
				sampler.stop(),
				if (status == ExecutionStatus.COMPLETED) null else "Job 상태가 COMPLETED가 아닙니다: ${jobExecution.status}",
			)
		} catch (_: TimeoutException) {
			future.cancel(true)
			JobExecutionOutcome(
				ExecutionStatus.TIMEOUT,
				elapsedMillis(startedAt),
				null,
				sampler.stop(),
				"Job이 timeout ${scenario.job.timeout} 안에 완료되지 않았습니다.",
			)
		} catch (exception: Throwable) {
			JobExecutionOutcome(
				ExecutionStatus.FAILED,
				elapsedMillis(startedAt),
				null,
				sampler.stop(),
				exception.failureMessage(),
			)
		} finally {
			executor.shutdownNow()
			executor.awaitTermination(5, TimeUnit.SECONDS)
		}
	}

	private fun validateExecution(
		jobExecution: JobExecution?,
		wallDurationMillis: Long,
		steps: List<StepMeasurement>,
		expected: ExpectedSettlement,
		context: ConfigurableApplicationContext,
		jdbcTemplate: JdbcTemplate,
	): ValidationReport {
		if (jobExecution == null) return ValidationReport(false, emptyMap(), listOf("JobExecution 결과가 없습니다."))
		val jobFailures = mutableListOf<String>()
		if (jobExecution.status != BatchStatus.COMPLETED) {
			jobFailures += "Job 상태가 COMPLETED가 아닙니다: ${jobExecution.status}"
		}
		val batchDurationMillis = if (jobExecution.startTime != null && jobExecution.endTime != null) {
			Duration.between(jobExecution.startTime, jobExecution.endTime).toMillis()
		} else {
			jobFailures += "Spring Batch Job 시작 또는 종료 시각이 없습니다."
			-1L
		}
		var report = ValidationReport(
			jobFailures.isEmpty(),
			linkedMapOf(
				"job.wall.duration.millis" to wallDurationMillis.toString(),
				"job.batch.duration.millis" to batchDurationMillis.toString(),
			),
			jobFailures,
		)
		val tuning = context.getBean(DailySellerSettlementBatchProperties::class.java)
		report = report.merge(
			BatchCounterValidator(tuning.chunkSize).validate(
				steps,
				expected.detailCount,
				expected.sellerSettlementCount,
			),
		)
		val settlementRunId = jdbcTemplate.query(
			"SELECT id FROM settlement_runs ORDER BY id",
			{ resultSet, _ -> resultSet.getLong("id") },
		).singleOrNull()
		if (settlementRunId == null) {
			return report.merge(ValidationReport(false, emptyMap(), listOf("SettlementRun 결과가 없습니다.")))
		}
		return report.merge(
			SettlementResultValidator(jdbcTemplate).validate(
				settlementRunId,
				ExpectedSettlementValues(
					detailCount = expected.detailCount,
					sellerSettlementCount = expected.sellerSettlementCount,
					grossAmount = expected.grossAmount,
					platformFeeAmount = expected.platformFeeAmount,
					netAmount = expected.netAmount,
				),
			),
		)
	}

	private fun startContext(database: IterationDatabase): ConfigurableApplicationContext {
		val arguments = database.datasourceProperties().map { (name, value) -> "--$name=$value" } + listOf(
			"--spring.flyway.enabled=false",
			"--spring.batch.job.enabled=false",
			"--spring.batch.jdbc.initialize-schema=never",
		)
		return SpringApplicationBuilder(BatchApplication::class.java)
			.web(WebApplicationType.NONE)
			.run(*arguments.toTypedArray())
	}

	private fun captureEffectiveEnvironment(
		context: ConfigurableApplicationContext,
		jdbcTemplate: JdbcTemplate,
		scenarioValues: MutableMap<String, String>,
	) {
		val tuning = context.getBean(DailySellerSettlementBatchProperties::class.java)
		val dataSource = context.getBean(javax.sql.DataSource::class.java) as HikariDataSource
		scenarioValues.putIfAbsent("japda.batch.daily-seller-settlement.chunk-size", tuning.chunkSize.toString())
		scenarioValues.putIfAbsent("japda.batch.daily-seller-settlement.page-size", tuning.pageSize.toString())
		scenarioValues.putIfAbsent("japda.batch.daily-seller-settlement.fetch-size", tuning.fetchSize.toString())
		scenarioValues.putIfAbsent("spring.datasource.hikari.maximum-pool-size", dataSource.maximumPoolSize.toString())
		scenarioValues.putIfAbsent("spring.datasource.hikari.minimum-idle", dataSource.minimumIdle.toString())
		scenarioValues.putIfAbsent("spring.datasource.hikari.connection-timeout", dataSource.connectionTimeout.toString())
		scenarioValues.putIfAbsent("spring.datasource.url", dataSource.jdbcUrl)
		scenarioValues.putIfAbsent("postgres.version", jdbcTemplate.queryForObject("SHOW server_version", String::class.java)!!)
	}

	private fun baseScenarioValues(runId: String, scenario: PerformanceScenario) = linkedMapOf(
		"run.id" to runId,
		"dataset.distribution" to "uniform",
		"japda.performance.dataset.seller-count" to scenario.dataset.sellerCount.toString(),
		"japda.performance.dataset.order-count" to scenario.dataset.orderCount.toString(),
		"japda.performance.dataset.random-seed" to scenario.dataset.randomSeed.toString(),
		"japda.performance.dataset.gross-amount" to scenario.dataset.grossAmount.toString(),
		"japda.performance.job.settlement-date" to scenario.job.settlementDate.toString(),
		"japda.performance.job.platform-fee-rate-bps" to scenario.job.platformFeeRateBps.toString(),
		"japda.performance.job.timeout" to scenario.job.timeout.toString(),
		"japda.performance.warmup-iterations" to scenario.warmupIterations.toString(),
		"japda.performance.measurement-iterations" to scenario.measurementIterations.toString(),
		"japda.performance.resource-sampling-interval-ms" to scenario.resourceSamplingInterval.toMillis().toString(),
		"jvm.version" to Runtime.version().toString(),
		"jvm.vendor" to System.getProperty("java.vendor", "UNKNOWN"),
		"os.name" to System.getProperty("os.name", "UNKNOWN"),
		"os.version" to System.getProperty("os.version", "UNKNOWN"),
		"os.arch" to System.getProperty("os.arch", "UNKNOWN"),
	)

	private fun JobExecution.stepMeasurements(): List<StepMeasurement> = stepExecutions
		.sortedBy { it.startTime }
		.map { step ->
			StepMeasurement(
				name = step.stepName,
				status = if (step.status == BatchStatus.COMPLETED) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED,
				durationMillis = if (step.startTime != null && step.endTime != null) {
					Duration.between(step.startTime, step.endTime).toMillis()
				} else {
					0
				},
				readCount = step.readCount,
				writeCount = step.writeCount,
				filterCount = step.filterCount,
				skipCount = step.readSkipCount + step.processSkipCount + step.writeSkipCount,
				commitCount = step.commitCount,
				rollbackCount = step.rollbackCount,
			)
		}

	private fun ValidationReport.prefixed(iterationIndex: Int) = ValidationReport(
		success = success,
		values = values.mapKeys { (key, _) -> "iteration.$iterationIndex.$key" },
		failures = failures.map { "iteration $iterationIndex: $it" },
	)

	private fun failedOutcome(index: Int, kind: IterationKind, exception: Throwable): IterationOutcome {
		val failure = exception.failureMessage()
		return IterationOutcome(
			measurement = IterationMeasurement(index, kind, ExecutionStatus.FAILED, 0, 0, 0, 0, 0, 0, emptyList(), false),
			resources = emptyList(),
			validation = ValidationReport(false, emptyMap(), listOf(failure)),
			failure = failure,
		)
	}

	private fun printSummary(
		runId: String,
		resultDirectory: Path,
		scenario: PerformanceScenario,
		scenarioValues: Map<String, String>,
		iterations: List<IterationMeasurement>,
		resources: List<ResourceSample>,
		validation: ValidationReport,
	) {
		val durations = iterations.filter {
			it.kind == IterationKind.MEASUREMENT && it.status == ExecutionStatus.COMPLETED && it.validationSuccess
		}.map { it.jobDurationMillis }
		println("성능 테스트 실행 ID: $runId")
		println("성능 테스트 결과 디렉터리: ${resultDirectory.toAbsolutePath()}")
		println("반복: warm-up=${scenario.warmupIterations}, measurement=${scenario.measurementIterations}")
		println("데이터: sellers=${scenario.dataset.sellerCount}, orders=${scenario.dataset.orderCount}")
		println(
			"설정: chunk=${scenarioValues["japda.batch.daily-seller-settlement.chunk-size"] ?: "UNKNOWN"}, " +
				"page=${scenarioValues["japda.batch.daily-seller-settlement.page-size"] ?: "UNKNOWN"}, " +
				"fetch=${scenarioValues["japda.batch.daily-seller-settlement.fetch-size"] ?: "UNKNOWN"}, " +
				"pool=${scenarioValues["spring.datasource.hikari.maximum-pool-size"] ?: "UNKNOWN"}",
		)
		if (durations.isNotEmpty()) {
			val statistics = PerformanceStatistics.calculate(durations)
			val throughput = scenario.dataset.orderCount * 1_000.0 / statistics.average
			println(
				"Job: min=${statistics.minimum}ms, max=${statistics.maximum}ms, avg=${"%.3f".format(statistics.average)}ms, " +
					"throughput=${"%.3f".format(throughput)} orders/s",
			)
		} else {
			println("Job: 유효한 측정 결과 없음")
		}
		val resourceAvailable = resources.isNotEmpty() && resources.all {
			it.postgresAvailability == ResourceAvailability.AVAILABLE
		}
		println("검산: ${if (validation.success) "SUCCESS" else "FAILED"}, 자원 측정: ${if (resourceAvailable) "AVAILABLE" else "UNAVAILABLE"}")
	}

	private fun requiredSystemProperty(name: String): String =
		System.getProperty(name)?.takeIf(String::isNotBlank)
			?: error("필수 내부 설정이 없습니다: $name")

	private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

	private fun Throwable.failureMessage(): String =
		"${this::class.simpleName ?: "오류"}: ${message ?: "메시지 없음"}"

	private data class JobExecutionOutcome(
		val status: ExecutionStatus,
		val wallDurationMillis: Long,
		val jobExecution: JobExecution?,
		val resources: List<ResourceSample>,
		val failure: String?,
	)

	private data class IterationOutcome(
		val measurement: IterationMeasurement,
		val resources: List<ResourceSample>,
		val validation: ValidationReport,
		val failure: String?,
	)
}
