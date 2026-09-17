package io.github.sehako.japda.batch.performance.report

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("성능 통계")
class PerformanceStatisticsTest {
	@Test
	fun 표본_통계_nearest_rank_percentile을_계산한다() {
		val statistics = PerformanceStatistics.calculate(listOf(1, 2, 3, 4, 100).map(Int::toLong))

		assertThat(statistics.sampleCount).isEqualTo(5)
		assertThat(statistics.minimum).isEqualTo(1)
		assertThat(statistics.maximum).isEqualTo(100)
		assertThat(statistics.average).isEqualTo(22.0)
		assertThat(statistics.p50).isEqualTo(3)
		assertThat(statistics.p95).isEqualTo(100)
		assertThat(statistics.p99).isEqualTo(100)
	}
}
