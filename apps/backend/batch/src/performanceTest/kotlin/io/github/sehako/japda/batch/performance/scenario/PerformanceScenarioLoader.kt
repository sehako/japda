package io.github.sehako.japda.batch.performance.scenario

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment

class PerformanceScenarioLoader(
	private val environment: Environment = StandardEnvironment(),
	private val clock: Clock = Clock.system(SEOUL_ZONE),
) {
	fun load(): PerformanceScenario {
		assertNoExternalDatasourceOverride()
		val sellerCount = required<Int>("japda.performance.dataset.seller-count")
		val orderCount = required<Int>("japda.performance.dataset.order-count")
		val generationBatchSize = required<Int>("japda.performance.dataset.generation-batch-size")
		val randomSeed = optional("japda.performance.dataset.random-seed", 1L)
		val grossAmount = required<Long>("japda.performance.dataset.gross-amount")
		val settlementDate = required<LocalDate>("japda.performance.job.settlement-date")
		val feeRate = required<Int>("japda.performance.job.platform-fee-rate-bps")
		val timeout = optional("japda.performance.job.timeout", Duration.ofMinutes(30))
		val warmupIterations = optional("japda.performance.warmup-iterations", 0)
		val measurementIterations = optional("japda.performance.measurement-iterations", 1)
		val samplingIntervalMillis = optional("japda.performance.resource-sampling-interval-ms", 100L)

		validate(
			sellerCount,
			orderCount,
			generationBatchSize,
			grossAmount,
			settlementDate,
			feeRate,
			timeout,
			warmupIterations,
			measurementIterations,
			samplingIntervalMillis,
		)

		return PerformanceScenario(
			dataset = DatasetScenario(sellerCount, orderCount, generationBatchSize, randomSeed, grossAmount),
			job = SettlementJobScenario(settlementDate, feeRate, timeout),
			warmupIterations = warmupIterations,
			measurementIterations = measurementIterations,
			resourceSamplingInterval = Duration.ofMillis(samplingIntervalMillis),
		)
	}

	private fun assertNoExternalDatasourceOverride() {
		EXTERNAL_DATASOURCE_PROPERTIES.forEach { property ->
			if (environment.getProperty(property) != null || binder.bind(property, String::class.java).isBound) {
				throw ExternalDatasourceOverrideException("외부 datasource 설정은 허용하지 않습니다: $property")
			}
		}
		DATASOURCE_PLACEHOLDER_PROPERTIES.forEach { property ->
			if (environment.getProperty(property) != null) {
				throw ExternalDatasourceOverrideException("외부 datasource 설정은 허용하지 않습니다: $property")
			}
		}
	}

	private inline fun <reified T : Any> required(property: String): T =
		bind(property, Bindable.of(T::class.java))
			?: throw PerformanceScenarioConfigurationException("필수 설정이 없습니다: $property")

	private inline fun <reified T : Any> optional(property: String, defaultValue: T): T =
		bind(property, Bindable.of(T::class.java)) ?: defaultValue

	private fun <T : Any> bind(property: String, target: Bindable<T>): T? = try {
		binder.bind(property, target).orElse(null)
	} catch (exception: BindException) {
		throw PerformanceScenarioConfigurationException("설정 형식이 올바르지 않습니다: $property", exception)
	}

	private fun validate(
		sellerCount: Int,
		orderCount: Int,
		generationBatchSize: Int,
		grossAmount: Long,
		settlementDate: LocalDate,
		feeRate: Int,
		timeout: Duration,
		warmupIterations: Int,
		measurementIterations: Int,
		samplingIntervalMillis: Long,
	) {
		invalidIf(sellerCount < 1, "seller-count는 1 이상이어야 합니다.")
		invalidIf(orderCount < sellerCount, "order-count는 seller-count 이상이어야 합니다.")
		invalidIf(generationBatchSize < 1, "generation-batch-size는 1 이상이어야 합니다.")
		invalidIf(grossAmount <= 0, "gross-amount는 양수여야 합니다.")
		try {
			Math.multiplyExact(grossAmount, orderCount.toLong())
		} catch (_: ArithmeticException) {
			throw PerformanceScenarioConfigurationException("gross-amount와 order-count의 곱이 BIGINT 범위를 넘습니다.")
		}
		invalidIf(
			!settlementDate.isBefore(LocalDate.now(clock.withZone(SEOUL_ZONE))),
			"settlement-date는 한국 시간 기준 과거 날짜여야 합니다.",
		)
		invalidIf(feeRate !in 0..10_000, "platform-fee-rate-bps는 0 이상 10000 이하여야 합니다.")
		invalidIf(timeout.isZero || timeout.isNegative, "job.timeout은 양수여야 합니다.")
		invalidIf(warmupIterations < 0, "warmup-iterations는 0 이상이어야 합니다.")
		invalidIf(measurementIterations < 1, "measurement-iterations는 1 이상이어야 합니다.")
		invalidIf(samplingIntervalMillis <= 0, "resource-sampling-interval-ms는 양수여야 합니다.")
	}

	private fun invalidIf(condition: Boolean, message: String) {
		if (condition) throw PerformanceScenarioConfigurationException(message)
	}

	private val binder = Binder.get(environment)

	private companion object {
		val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
		val EXTERNAL_DATASOURCE_PROPERTIES = listOf(
			"spring.datasource.url",
			"spring.datasource.username",
			"spring.datasource.password",
		)
		val DATASOURCE_PLACEHOLDER_PROPERTIES = listOf("DB_URL", "DB_USERNAME", "DB_PASSWORD")
	}
}

class PerformanceScenarioConfigurationException(
	message: String,
	cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class ExternalDatasourceOverrideException(message: String) : IllegalArgumentException(message)
