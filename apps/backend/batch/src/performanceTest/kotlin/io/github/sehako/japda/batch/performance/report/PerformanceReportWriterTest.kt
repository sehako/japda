package io.github.sehako.japda.batch.performance.report

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.IterationKind
import io.github.sehako.japda.batch.performance.measurement.model.IterationMeasurement
import io.github.sehako.japda.batch.performance.measurement.model.ResourceAvailability
import io.github.sehako.japda.batch.performance.measurement.model.ResourceSample
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import io.github.sehako.japda.batch.performance.validation.ValidationReport
import java.nio.file.Files
import kotlin.io.path.readText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("성능 결과 기록기")
class PerformanceReportWriterTest {
	@Test
	fun 결과_여섯_파일을_UTF8로_기록하고_credential을_제외한다() {
		val directory = Files.createTempDirectory("settlement-performance-report")
		val warmup = iteration(0, IterationKind.WARMUP, 9_999)
		val measurement = iteration(1, IterationKind.MEASUREMENT, 250)
		val resource = ResourceSample(
			iterationIndex = 1,
			elapsedMillis = 10,
			jvmHeapUsedBytes = 100,
			processCpuLoad = 0.25,
			postgresCpuPercent = null,
			postgresMemoryBytes = null,
			postgresAvailability = ResourceAvailability.UNAVAILABLE,
			postgresUnavailableReason = "Docker stats, 권한 없음",
		)
		val validation = ValidationReport(
			success = true,
			values = linkedMapOf("expected.order.count" to "2", "actual.order.count" to "2"),
			failures = emptyList(),
		)

		PerformanceReportWriter(directory).write(
			scenario = linkedMapOf(
				"dataset.seller.count" to "1",
				"spring.datasource.url" to "jdbc:postgresql://localhost/test?user=ignored&password=secret",
				"spring.datasource.password" to "절대 기록하면 안 됨",
			),
			iterations = listOf(warmup, measurement),
			resources = listOf(resource),
			validation = validation,
		)

		assertThat(directory.toFile().list()!!.toSet()).containsExactlyInAnyOrder(
			"scenario.properties", "iterations.csv", "steps.csv", "resources.csv",
			"validation.properties", "summary.properties",
		)
		val allText = directory.toFile().listFiles()!!.joinToString("\n") { it.readText() }
		assertThat(allText).doesNotContain("secret", "절대 기록하면 안 됨", "password=")
		assertThat(directory.resolve("resources.csv").readText()).contains("UNAVAILABLE", "\"Docker stats, 권한 없음\"")
		assertThat(directory.resolve("summary.properties").readText())
			.contains(
				"job.duration.sample.count=1",
				"job.duration.p50.millis=250",
				"job.throughput.average.per-second=8.000",
				"overall.success=true",
			)
			.doesNotContain("throughput.milli", "throughput.average.millis")
			.doesNotContain("9999")
	}

	private fun iteration(index: Int, kind: IterationKind, durationMillis: Long) = IterationMeasurement(
		index = index,
		kind = kind,
		status = ExecutionStatus.COMPLETED,
		migrationMillis = 1,
		dataCalculationMillis = 2,
		dataInsertMillis = 3,
		contextStartMillis = 4,
		jobDurationMillis = durationMillis,
		processedOrderCount = 2,
		steps = listOf(
			StepMeasurement("collectSettlementDetailsStep", ExecutionStatus.COMPLETED, durationMillis / 2, 2, 2, 0, 0, 1, 0),
		),
		validationSuccess = true,
	)
}
