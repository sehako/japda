package io.github.sehako.japda.batch.performance.report

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.diagnostic.QueryPlanDiagnostic
import io.github.sehako.japda.batch.performance.measurement.model.IterationKind
import io.github.sehako.japda.batch.performance.measurement.model.IterationMeasurement
import io.github.sehako.japda.batch.performance.measurement.model.ResourceAvailability
import io.github.sehako.japda.batch.performance.measurement.model.ResourceSample
import io.github.sehako.japda.batch.performance.validation.ValidationReport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.math.ceil

data class PerformanceStatistics(
	val sampleCount: Int,
	val minimum: Long,
	val maximum: Long,
	val average: Double,
	val p50: Long,
	val p95: Long,
	val p99: Long,
) {
	companion object {
		fun calculate(values: List<Long>): PerformanceStatistics {
			require(values.isNotEmpty()) { "성능 통계 표본은 하나 이상이어야 합니다." }
			val sorted = values.sorted()
			fun percentile(percent: Int): Long = sorted[(ceil(percent / 100.0 * sorted.size).toInt() - 1).coerceAtLeast(0)]
			return PerformanceStatistics(
				sampleCount = sorted.size,
				minimum = sorted.first(),
				maximum = sorted.last(),
				average = sorted.average(),
				p50 = percentile(50),
				p95 = percentile(95),
				p99 = percentile(99),
			)
		}
	}
}

class PerformanceReportWriter(
	private val resultDirectory: Path,
) {
	fun write(
		scenario: Map<String, String>,
		iterations: List<IterationMeasurement>,
		resources: List<ResourceSample>,
		validation: ValidationReport,
		queryPlans: List<QueryPlanDiagnostic> = emptyList(),
	) {
		Files.createDirectories(resultDirectory)
		writeProperties("scenario.properties", sanitizeScenario(scenario))
		writeIterations(iterations)
		writeSteps(iterations)
		writeResources(resources)
		writeValidation(validation)
		writeSummary(iterations, resources, validation)
		writeQueryPlans(queryPlans)
	}

	private fun writeQueryPlans(queryPlans: List<QueryPlanDiagnostic>) {
		val content = queryPlans.joinToString("\n\n") { diagnostic ->
			"cursor=${diagnostic.cursorPosition}\nexecution_millis=${diagnostic.executionMillis}\nreturned_row_count=${diagnostic.returnedRowCount}\n${diagnostic.plan}"
		}
		write("query-plans.txt", if (content.isEmpty()) "" else "$content\n")
	}

	private fun writeIterations(iterations: List<IterationMeasurement>) {
		val rows = iterations.map { iteration ->
			listOf(
				iteration.index, iteration.kind, iteration.status, iteration.migrationMillis,
				iteration.dataCalculationMillis, iteration.dataInsertMillis, iteration.contextStartMillis,
				iteration.jobDurationMillis, iteration.processedOrderCount,
				formatDecimal(iteration.throughputPerSecond), iteration.validationSuccess,
			)
		}
		writeCsv(
			"iterations.csv",
			listOf("iteration", "kind", "status", "migration_millis", "data_calculation_millis", "data_insert_millis", "context_start_millis", "job_duration_millis", "processed_order_count", "throughput_per_second", "validation_success"),
			rows,
		)
	}

	private fun writeSteps(iterations: List<IterationMeasurement>) {
		val rows = iterations.flatMap { iteration ->
			iteration.steps.map { step ->
				listOf(
					iteration.index, iteration.kind, step.name, step.status, step.durationMillis,
					step.readCount, step.writeCount, step.filterCount, step.skipCount,
					step.commitCount, step.rollbackCount,
					formatDecimal(if (step.durationMillis > 0) step.writeCount * 1_000.0 / step.durationMillis else 0.0),
				)
			}
		}
		writeCsv(
			"steps.csv",
			listOf("iteration", "kind", "step", "status", "duration_millis", "read_count", "write_count", "filter_count", "skip_count", "commit_count", "rollback_count", "throughput_per_second"),
			rows,
		)
	}

	private fun writeResources(resources: List<ResourceSample>) {
		val rows = resources.map { sample ->
			listOf(
				sample.iterationIndex, sample.elapsedMillis, sample.jvmHeapUsedBytes,
				sample.processCpuLoad, sample.postgresCpuPercent, sample.postgresMemoryBytes,
				sample.postgresAvailability, sample.postgresUnavailableReason,
			)
		}
		writeCsv(
			"resources.csv",
			listOf("iteration", "elapsed_millis", "jvm_heap_used_bytes", "process_cpu_load", "postgres_cpu_percent", "postgres_memory_bytes", "postgres_availability", "postgres_unavailable_reason"),
			rows,
		)
	}

	private fun writeValidation(validation: ValidationReport) {
		val values = linkedMapOf<String, String>()
		values.putAll(validation.values)
		values["validation.success"] = validation.success.toString()
		values["validation.failure.count"] = validation.failures.size.toString()
		validation.failures.forEachIndexed { index, failure -> values["validation.failure.$index"] = failure }
		writeProperties("validation.properties", values)
	}

	private fun writeSummary(
		iterations: List<IterationMeasurement>,
		resources: List<ResourceSample>,
		validation: ValidationReport,
	) {
		val successfulMeasurements = iterations.filter {
			it.kind == IterationKind.MEASUREMENT && it.status == ExecutionStatus.COMPLETED && it.validationSuccess
		}
		val values = linkedMapOf<String, String>()
		if (successfulMeasurements.isNotEmpty()) {
			values.putStatistics("job.duration", PerformanceStatistics.calculate(successfulMeasurements.map { it.jobDurationMillis }))
			values.putThroughputStatistics(successfulMeasurements.map { it.throughputPerSecond })
			val stepNames = successfulMeasurements.flatMap { it.steps }.map { it.name }.toSortedSet()
			for (stepName in stepNames) {
				val durations = successfulMeasurements.mapNotNull { iteration ->
					iteration.steps.singleOrNull { it.name == stepName }?.durationMillis
				}
				if (durations.size == successfulMeasurements.size) {
					values.putStatistics("step.$stepName.duration", PerformanceStatistics.calculate(durations))
				}
			}
		}
		val resourceAvailable = resources.isNotEmpty() && resources.all {
			it.postgresAvailability == ResourceAvailability.AVAILABLE
		}
		values["resource.measurement.complete"] = resourceAvailable.toString()
		values["measurement.success.count"] = successfulMeasurements.size.toString()
		values["overall.success"] = (
			validation.success && iterations.all { it.status == ExecutionStatus.COMPLETED && it.validationSuccess }
		).toString()
		writeProperties("summary.properties", values)
	}

	private fun MutableMap<String, String>.putStatistics(prefix: String, statistics: PerformanceStatistics) {
		this["$prefix.sample.count"] = statistics.sampleCount.toString()
		this["$prefix.minimum.millis"] = statistics.minimum.toString()
		this["$prefix.maximum.millis"] = statistics.maximum.toString()
		this["$prefix.average.millis"] = formatDecimal(statistics.average)
		this["$prefix.p50.millis"] = statistics.p50.toString()
		this["$prefix.p95.millis"] = statistics.p95.toString()
		this["$prefix.p99.millis"] = statistics.p99.toString()
	}

	private fun MutableMap<String, String>.putThroughputStatistics(values: List<Double>) {
		val statistics = PerformanceStatistics.calculate(values.map { (it * 1_000).toLong() })
		this["job.throughput.sample.count"] = statistics.sampleCount.toString()
		this["job.throughput.minimum.per-second"] = formatDecimal(statistics.minimum / 1_000.0)
		this["job.throughput.maximum.per-second"] = formatDecimal(statistics.maximum / 1_000.0)
		this["job.throughput.average.per-second"] = formatDecimal(statistics.average / 1_000.0)
		this["job.throughput.p50.per-second"] = formatDecimal(statistics.p50 / 1_000.0)
		this["job.throughput.p95.per-second"] = formatDecimal(statistics.p95 / 1_000.0)
		this["job.throughput.p99.per-second"] = formatDecimal(statistics.p99 / 1_000.0)
	}

	private fun sanitizeScenario(scenario: Map<String, String>): Map<String, String> = scenario
		.filterKeys { key ->
			val normalized = key.lowercase(Locale.ROOT)
			!normalized.contains("password") && !normalized.contains("credential") && !normalized.contains("token")
		}
		.mapValues { (key, value) -> if (key.endsWith("url", ignoreCase = true)) sanitizeJdbcUrl(value) else value }

	private fun sanitizeJdbcUrl(value: String): String {
		val parts = value.split('?', limit = 2)
		if (parts.size == 1) return value
		val safeQuery = parts[1].split('&').filterNot { parameter ->
			val name = parameter.substringBefore('=').lowercase(Locale.ROOT)
			name in setOf("user", "username", "password", "token", "apikey", "api_key")
		}
		return if (safeQuery.isEmpty()) parts[0] else "${parts[0]}?${safeQuery.joinToString("&")}"
	}

	private fun writeProperties(fileName: String, values: Map<String, String>) {
		val content = values.toSortedMap().entries.joinToString("\n", postfix = if (values.isEmpty()) "" else "\n") {
			"${escapeProperty(it.key)}=${escapeProperty(it.value)}"
		}
		write(fileName, content)
	}

	private fun writeCsv(fileName: String, header: List<String>, rows: List<List<Any?>>) {
		val content = buildString {
			appendLine(header.joinToString(","))
			rows.forEach { row -> appendLine(row.joinToString(",") { csv(it?.toString().orEmpty()) }) }
		}
		write(fileName, content)
	}

	private fun write(fileName: String, content: String) {
		Files.writeString(
			resultDirectory.resolve(fileName),
			content,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
		)
	}

	private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
		"\"${value.replace("\"", "\"\"")}\""
	} else {
		value
	}

	private fun escapeProperty(value: String): String = buildString {
		value.forEachIndexed { index, character ->
			when (character) {
				'\\' -> append("\\\\")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				'\t' -> append("\\t")
				'=', ':' -> append('\\').append(character)
				' ', '#', '!' -> if (index == 0) append('\\').append(character) else append(character)
				else -> append(character)
			}
		}
	}

	private fun formatDecimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
