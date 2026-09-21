package io.github.sehako.japda.batch.performance.report

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.IterationKind
import io.github.sehako.japda.batch.performance.measurement.model.IterationMeasurement
import io.github.sehako.japda.batch.performance.measurement.model.ResourceAvailability
import io.github.sehako.japda.batch.performance.measurement.model.ResourceSample
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import io.github.sehako.japda.batch.performance.diagnostic.QueryPlanDiagnostic
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
			success = false,
			values = linkedMapOf(
				"expected.order.count" to "2",
				"actual.order.count" to "2",
				"iteration.1.partition.collection.longest.ratio" to "0.251",
			),
			failures = listOf("iteration 1: collection 파티션 처리 편향이 25.0%를 초과했습니다"),
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
			queryPlans = listOf(
				QueryPlanDiagnostic(
					partitionLabel = "collectionPartition032",
					partitionStartInclusive = 101,
					partitionEndExclusive = 201,
					partitionEndInclusive = false,
					cursorPosition = "middle",
					executionMillis = 12,
					plan = "Index Cond: [REDACTED_CURSOR]",
				),
			),
		)

		assertThat(directory.toFile().list()!!.toSet()).containsExactlyInAnyOrder(
			"scenario.properties", "iterations.csv", "steps.csv", "resources.csv",
			"validation.properties", "summary.properties", "query-plans.txt",
		)
		val allText = directory.toFile().listFiles()!!.joinToString("\n") { it.readText() }
		assertThat(allText).doesNotContain("secret", "절대 기록하면 안 됨", "password=")
		assertThat(directory.resolve("resources.csv").readText()).contains("UNAVAILABLE", "\"Docker stats, 권한 없음\"")
		assertThat(directory.resolve("iterations.csv").readText())
			.contains("processed_settlement_entry_count")
		assertThat(directory.resolve("summary.properties").readText())
			.contains(
				"job.duration.sample.count=1",
				"job.duration.p50.millis=250",
				"job.throughput.average.per-second=8.000",
				"overall.success=false",
				"iteration.1.partition.collection.longest.ratio=0.251",
				"partition.skew.success=false",
			)
			.doesNotContain("throughput.milli", "throughput.average.millis")
			.doesNotContain("9999")
		assertThat(directory.resolve("query-plans.txt").readText())
			.contains(
				"partition=collectionPartition032",
				"partition_start_inclusive=101",
				"partition_end_exclusive=201",
				"partition_end_inclusive=false",
				"cursor=middle",
				"execution_millis=12",
				"[REDACTED_CURSOR]",
			)
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
