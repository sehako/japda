package io.github.sehako.japda.batch.performance.scenario

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

@DisplayName("성능 테스트 시나리오 설정")
class PerformanceScenarioLoaderTest {
	@Test
	@DisplayName("필수 설정과 기본값을 context 없이 binding한다")
	fun 필수_설정과_기본값을_context_없이_binding한다() {
		val scenario = loader(validProperties()).load()

		assertEquals(3, scenario.dataset.sellerCount)
		assertEquals(7, scenario.dataset.orderCount)
		assertEquals(2, scenario.dataset.generationBatchSize)
		assertEquals(1L, scenario.dataset.randomSeed)
		assertEquals(10_000L, scenario.dataset.grossAmount)
		assertEquals(LocalDate.of(2026, 9, 15), scenario.job.settlementDate)
		assertEquals(1_000, scenario.job.platformFeeRateBps)
		assertEquals(Duration.ofMinutes(30), scenario.job.timeout)
		assertEquals(0, scenario.warmupIterations)
		assertEquals(1, scenario.measurementIterations)
		assertEquals(Duration.ofMillis(100), scenario.resourceSamplingInterval)
	}

	@Test
	@DisplayName("명시 설정은 기본값을 대체한다")
	fun 명시_설정은_기본값을_대체한다() {
		val properties = validProperties() + mapOf(
			"japda.performance.dataset.random-seed" to "42",
			"japda.performance.job.timeout" to "45s",
			"japda.performance.warmup-iterations" to "2",
			"japda.performance.measurement-iterations" to "5",
			"japda.performance.resource-sampling-interval-ms" to "250",
		)

		val scenario = loader(properties).load()

		assertEquals(42L, scenario.dataset.randomSeed)
		assertEquals(Duration.ofSeconds(45), scenario.job.timeout)
		assertEquals(2, scenario.warmupIterations)
		assertEquals(5, scenario.measurementIterations)
		assertEquals(Duration.ofMillis(250), scenario.resourceSamplingInterval)
	}

	@Test
	@DisplayName("필수 설정 누락은 명확한 설정 오류로 실패한다")
	fun 필수_설정_누락은_명확한_설정_오류로_실패한다() {
		val exception = assertFailsWith<PerformanceScenarioConfigurationException> {
			loader(validProperties() - "japda.performance.dataset.seller-count").load()
		}

		assertTrue(exception.message!!.contains("seller-count"))
	}

	@Test
	@DisplayName("생성 구간 크기 누락은 명확한 설정 오류로 실패한다")
	fun 생성_구간_크기_누락은_명확한_설정_오류로_실패한다() {
		val exception = assertFailsWith<PerformanceScenarioConfigurationException> {
			loader(validProperties() - "japda.performance.dataset.generation-batch-size").load()
		}

		assertTrue(exception.message!!.contains("generation-batch-size"))
	}

	@Test
	@DisplayName("생성 구간 크기는 1 미만이면 설정 오류로 실패한다")
	fun 생성_구간_크기는_1_미만이면_설정_오류로_실패한다() {
		listOf("0", "-1").forEach { invalidBatchSize ->
			val exception = assertFailsWith<PerformanceScenarioConfigurationException> {
				loader(
					validProperties() +
						("japda.performance.dataset.generation-batch-size" to invalidBatchSize),
				).load()
			}

			assertTrue(exception.message!!.contains("generation-batch-size"))
		}
	}

	@Test
	@DisplayName("범위와 날짜 및 overflow 오류를 실행 전에 거부한다")
	fun 범위와_날짜_및_overflow_오류를_실행_전에_거부한다() {
		val invalidProperties = listOf(
			validProperties() + ("japda.performance.dataset.seller-count" to "0"),
			validProperties() + ("japda.performance.dataset.order-count" to "2"),
			validProperties() + ("japda.performance.dataset.gross-amount" to "0"),
			validProperties() + ("japda.performance.dataset.order-count" to Long.MAX_VALUE.toString()),
			validProperties() + ("japda.performance.job.settlement-date" to "2026-09-16"),
			validProperties() + ("japda.performance.job.platform-fee-rate-bps" to "10001"),
			validProperties() + ("japda.performance.job.timeout" to "0s"),
			validProperties() + ("japda.performance.warmup-iterations" to "-1"),
			validProperties() + ("japda.performance.measurement-iterations" to "0"),
			validProperties() + ("japda.performance.resource-sampling-interval-ms" to "0"),
		)

		invalidProperties.forEach { properties ->
			assertFailsWith<PerformanceScenarioConfigurationException> { loader(properties).load() }
		}
	}

	@Test
	@DisplayName("외부 datasource 연결 정보는 container 생성 전에 거부한다")
	fun 외부_datasource_연결_정보는_container_생성_전에_거부한다() {
		listOf(
			"spring.datasource.url" to "jdbc:postgresql://external/production",
			"spring.datasource.username" to "external-user",
			"spring.datasource.password" to "secret",
			"DB_URL" to "jdbc:postgresql://external/production",
			"DB_USERNAME" to "external-user",
			"DB_PASSWORD" to "secret",
		).forEach { override ->
			val exception = assertFailsWith<ExternalDatasourceOverrideException> {
				loader(validProperties() + override).load()
			}
			assertTrue(exception.message!!.contains(override.first))
		}
	}

	private fun loader(properties: Map<String, String>): PerformanceScenarioLoader {
		val environment = StandardEnvironment().apply {
			propertySources.addFirst(MapPropertySource("test", properties))
		}
		return PerformanceScenarioLoader(environment, FIXED_CLOCK)
	}

	private fun validProperties() = mapOf(
		"japda.performance.dataset.seller-count" to "3",
		"japda.performance.dataset.order-count" to "7",
		"japda.performance.dataset.generation-batch-size" to "2",
		"japda.performance.dataset.gross-amount" to "10000",
		"japda.performance.job.settlement-date" to "2026-09-15",
		"japda.performance.job.platform-fee-rate-bps" to "1000",
	)

	private companion object {
		val FIXED_CLOCK: Clock = Clock.fixed(
			Instant.parse("2026-09-16T03:00:00Z"),
			ZoneId.of("Asia/Seoul"),
		)
	}
}
