package io.github.sehako.japda.batch.performance.measurement.model

enum class IterationKind {
	WARMUP,
	MEASUREMENT,
}

enum class ExecutionStatus {
	COMPLETED,
	FAILED,
	TIMEOUT,
}

data class StepMeasurement(
	val name: String,
	val status: ExecutionStatus,
	val durationMillis: Long,
	val readCount: Long,
	val writeCount: Long,
	val filterCount: Long,
	val skipCount: Long,
	val commitCount: Long,
	val rollbackCount: Long,
)

data class IterationMeasurement(
	val index: Int,
	val kind: IterationKind,
	val status: ExecutionStatus,
	val migrationMillis: Long,
	val dataCalculationMillis: Long,
	val dataInsertMillis: Long,
	val contextStartMillis: Long,
	val jobDurationMillis: Long,
	val processedOrderCount: Long,
	val steps: List<StepMeasurement>,
	val validationSuccess: Boolean,
) {
	val throughputPerSecond: Double
		get() = if (jobDurationMillis > 0) processedOrderCount * 1_000.0 / jobDurationMillis else 0.0
}

enum class ResourceAvailability {
	AVAILABLE,
	UNAVAILABLE,
}

data class PostgresResourceSnapshot(
	val cpuPercent: Double,
	val memoryBytes: Long,
)

data class ResourceSample(
	val iterationIndex: Int,
	val elapsedMillis: Long,
	val jvmHeapUsedBytes: Long,
	val processCpuLoad: Double?,
	val postgresCpuPercent: Double?,
	val postgresMemoryBytes: Long?,
	val postgresAvailability: ResourceAvailability,
	val postgresUnavailableReason: String?,
)
