package io.github.sehako.japda.batch.performance.measurement

import com.sun.management.OperatingSystemMXBean
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.StatsCmd
import com.github.dockerjava.api.model.Statistics
import io.github.sehako.japda.batch.performance.measurement.model.PostgresResourceSnapshot
import io.github.sehako.japda.batch.performance.measurement.model.ResourceAvailability
import io.github.sehako.japda.batch.performance.measurement.model.ResourceSample
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface PostgresResourceProbe {
	fun sample(): PostgresResourceSnapshot
}

internal class AwaitingPostgresResourceSnapshot(
	private val timeout: Duration,
) {
	private val ready = CountDownLatch(1)
	@Volatile
	private var snapshot: PostgresResourceSnapshot? = null
	@Volatile
	private var failure: Throwable? = null

	fun complete(value: PostgresResourceSnapshot) {
		snapshot = value
		ready.countDown()
	}

	fun fail(throwable: Throwable) {
		failure = throwable
		ready.countDown()
	}

	fun await(): PostgresResourceSnapshot {
		if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			throw IllegalStateException("PostgreSQL Docker stats 첫 표본을 $timeout 안에 받지 못했습니다.")
		}
		failure?.let { throw IllegalStateException("PostgreSQL Docker stats 수집 실패", it) }
		return snapshot ?: throw IllegalStateException("PostgreSQL Docker stats 첫 표본이 비어 있습니다.")
	}
}

class ResourceSampler(
	private val iterationIndex: Int,
	private val interval: Duration,
	private val postgresProbe: PostgresResourceProbe,
) : AutoCloseable {
	private val samples = Collections.synchronizedList(mutableListOf<ResourceSample>())
	private val started = AtomicBoolean(false)
	private var startedNanos: Long = 0
	private var executor: ScheduledExecutorService? = null

	init {
		require(!interval.isZero && !interval.isNegative) { "자원 표본 수집 간격은 양수여야 합니다." }
	}

	fun start() {
		check(started.compareAndSet(false, true)) { "자원 sampler는 한 번만 시작할 수 있습니다." }
		startedNanos = System.nanoTime()
		executor = Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "settlement-performance-resource-sampler").apply { isDaemon = true }
		}.also {
			it.scheduleAtFixedRate(::sampleSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
		}
	}

	fun stop(): List<ResourceSample> {
		if (!started.get()) return emptyList()
		executor?.shutdownNow()
		executor?.awaitTermination(5, TimeUnit.SECONDS)
		executor = null
		runCatching { (postgresProbe as? AutoCloseable)?.close() }
		return synchronized(samples) { samples.toList() }
	}

	override fun close() {
		stop()
	}

	private fun sampleSafely() {
		val memory = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
		val cpu = (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
			?.processCpuLoad
			?.takeIf { it >= 0.0 }
		val postgres = runCatching { postgresProbe.sample() }
		val snapshot = postgres.getOrNull()
		val reason = postgres.exceptionOrNull()?.let { exception ->
			buildString {
				append(exception::class.simpleName ?: "오류")
				exception.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
			}.take(500)
		}
		samples += ResourceSample(
			iterationIndex = iterationIndex,
			elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
			jvmHeapUsedBytes = memory,
			processCpuLoad = cpu,
			postgresCpuPercent = snapshot?.cpuPercent,
			postgresMemoryBytes = snapshot?.memoryBytes,
			postgresAvailability = if (snapshot == null) ResourceAvailability.UNAVAILABLE else ResourceAvailability.AVAILABLE,
			postgresUnavailableReason = reason,
		)
	}
}

class DockerStatsPostgresResourceProbe(
	private val dockerClient: DockerClient,
	private val containerId: String,
) : PostgresResourceProbe, AutoCloseable {
	@Volatile
	private var latest: PostgresResourceSnapshot? = null
	@Volatile
	private var failure: Throwable? = null
	private val firstSnapshot = AwaitingPostgresResourceSnapshot(Duration.ofSeconds(2))
	private var statsCommand: StatsCmd? = null
	private var callback: ResultCallback.Adapter<Statistics>? = null

	@Synchronized
	override fun sample(): PostgresResourceSnapshot {
		if (statsCommand == null) startStreaming()
		failure?.let { throw IllegalStateException("PostgreSQL Docker stats 수집 실패", it) }
		return latest ?: firstSnapshot.await()
	}

	private fun startStreaming() {
		check(containerId.isNotBlank()) { "PostgreSQL container id가 비어 있습니다." }
		val newCallback = object : ResultCallback.Adapter<Statistics>() {
			override fun onNext(statistics: Statistics) {
				val snapshot = statistics.toSnapshot()
				latest = snapshot
				firstSnapshot.complete(snapshot)
			}

			override fun onError(throwable: Throwable) {
				failure = throwable
				firstSnapshot.fail(throwable)
				super.onError(throwable)
			}
		}
		callback = newCallback
		statsCommand = dockerClient.statsCmd(containerId).also { it.exec(newCallback) }
	}

	override fun close() {
		callback?.close()
		statsCommand?.close()
		callback = null
		statsCommand = null
	}

	private fun Statistics.toSnapshot(): PostgresResourceSnapshot {
		val cpuDelta = (cpuStats.cpuUsage?.totalUsage ?: 0L) - (preCpuStats.cpuUsage?.totalUsage ?: 0L)
		val systemDelta = (cpuStats.systemCpuUsage ?: 0L) - (preCpuStats.systemCpuUsage ?: 0L)
		val cpuCount = cpuStats.onlineCpus
			?: cpuStats.cpuUsage?.percpuUsage?.size?.toLong()
			?: 1L
		val cpuPercent = if (cpuDelta > 0 && systemDelta > 0) {
			cpuDelta.toDouble() / systemDelta.toDouble() * cpuCount * 100.0
		} else {
			0.0
		}
		return PostgresResourceSnapshot(
			cpuPercent = cpuPercent,
			memoryBytes = memoryStats.usage ?: 0L,
		)
	}
}
