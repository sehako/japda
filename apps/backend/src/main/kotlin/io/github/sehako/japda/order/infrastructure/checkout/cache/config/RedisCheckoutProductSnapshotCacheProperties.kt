package io.github.sehako.japda.order.infrastructure.checkout.cache.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("order.checkout.cache")
data class RedisCheckoutProductSnapshotCacheProperties(
	val enabled: Boolean = false,
	val namespace: String = "japda",
	val host: String = "localhost",
	val port: Int = 6379,
	val connectTimeout: Duration = Duration.ofMillis(500),
	val commandTimeout: Duration = Duration.ofMillis(200),
	val freshTtl: Duration = Duration.ofMinutes(5),
	val staleTtl: Duration = Duration.ofMinutes(55),
	val physicalTtl: Duration = Duration.ofMinutes(65),
) {
	init {
		require(!freshTtl.isNegative && !freshTtl.isZero) { "freshTtl은 양수여야 합니다." }
		require(!staleTtl.isNegative && !staleTtl.isZero) { "staleTtl은 양수여야 합니다." }
		require(physicalTtl > freshTtl.plus(staleTtl)) { "physicalTtl은 freshTtl과 staleTtl의 합보다 커야 합니다." }
	}
}
