package io.github.sehako.japda.batch.performance.measurement

import io.github.sehako.japda.batch.performance.measurement.model.PostgresResourceSnapshot
import io.github.sehako.japda.batch.performance.measurement.model.ResourceAvailability
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Job 구간 자원 sampler")
class ResourceSamplerTest {
	@Test
	fun PostgreSQL_stats가_실패해도_UNAVAILABLE_원인을_기록한다() {
		val count = AtomicInteger()
		val sampler = ResourceSampler(
			iterationIndex = 1,
			interval = Duration.ofMillis(5),
			postgresProbe = PostgresResourceProbe {
				count.incrementAndGet()
				throw IllegalStateException("Docker socket 접근 불가")
			},
		)

		sampler.start()
		Thread.sleep(30)
		val samples = sampler.stop()

		assertThat(count.get()).isPositive()
		assertThat(samples).isNotEmpty
		assertThat(samples).allMatch { it.postgresAvailability == ResourceAvailability.UNAVAILABLE }
		assertThat(samples).allMatch { it.postgresUnavailableReason!!.contains("Docker socket 접근 불가") }
	}

	@Test
	fun PostgreSQL_stats가_가능하면_CPU와_memory를_기록한다() {
		val sampler = ResourceSampler(
			iterationIndex = 2,
			interval = Duration.ofMillis(5),
			postgresProbe = PostgresResourceProbe { PostgresResourceSnapshot(12.5, 4096) },
		)

		sampler.start()
		Thread.sleep(20)
		val samples = sampler.stop()

		assertThat(samples).allMatch { it.postgresAvailability == ResourceAvailability.AVAILABLE }
		assertThat(samples).allMatch { it.postgresCpuPercent == 12.5 && it.postgresMemoryBytes == 4096L }
	}

	@Test
	fun PostgreSQL_probe_종료가_실패해도_Job_측정_결과를_반환한다() {
		val probe = object : PostgresResourceProbe, AutoCloseable {
			override fun sample() = PostgresResourceSnapshot(1.0, 1)
			override fun close() = throw IllegalStateException("stats stream 종료 실패")
		}
		val sampler = ResourceSampler(3, Duration.ofMillis(5), probe)

		sampler.start()
		Thread.sleep(20)

		assertThat(sampler.stop()).isNotEmpty
	}

	@Test
	fun Docker_stats_첫_표본이_비동기로_도착하면_짧게_기다려_사용한다() {
		val firstSnapshot = AwaitingPostgresResourceSnapshot(Duration.ofMillis(200))
		thread(isDaemon = true) {
			Thread.sleep(20)
			firstSnapshot.complete(PostgresResourceSnapshot(3.5, 2048))
		}

		assertThat(firstSnapshot.await()).isEqualTo(PostgresResourceSnapshot(3.5, 2048))
	}
}
