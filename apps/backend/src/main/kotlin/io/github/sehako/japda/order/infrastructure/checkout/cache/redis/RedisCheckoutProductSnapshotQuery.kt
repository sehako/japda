package io.github.sehako.japda.order.infrastructure.checkout.cache.redis

import tools.jackson.databind.ObjectMapper
import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshot
import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshotQuery
import io.github.sehako.japda.order.infrastructure.checkout.cache.config.RedisCheckoutProductSnapshotCacheProperties
import io.github.sehako.japda.order.infrastructure.checkout.cache.key.RedisCheckoutProductSnapshotKeyFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executor
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate

internal class RedisCheckoutProductSnapshotQuery(
	private val redisTemplate: StringRedisTemplate,
	private val sourceQuery: CheckoutProductSnapshotQuery,
	private val keyFactory: RedisCheckoutProductSnapshotKeyFactory,
	private val properties: RedisCheckoutProductSnapshotCacheProperties,
	private val objectMapper: ObjectMapper,
	private val refreshExecutor: Executor,
	private val meterRegistry: MeterRegistry,
	private val clock: Clock = Clock.systemUTC(),
) : CheckoutProductSnapshotQuery {
	override fun findBySaleId(saleId: Long): CheckoutProductSnapshot? {
		val now = Instant.now(clock)
		when (val cached = read(saleId)) {
			is CachedValue.Fresh -> return cached.snapshot.also { recordCacheResult("fresh-hit") }
			is CachedValue.Stale -> {
				recordCacheResult("stale-hit")
				refreshExecutor.execute { refresh(saleId) }
				return cached.snapshot
			}
			CachedValue.Missing -> return loadAndStore(saleId, now).also { recordCacheResult("miss"); recordRefreshResult("loaded") }
			CachedValue.Expired -> return loadAndStore(saleId, now).also { recordCacheResult("hard-expire"); recordRefreshResult("loaded") }
		}
	}

	private fun refresh(saleId: Long) {
		try {
			loadAndStore(saleId, Instant.now(clock))
			recordRefreshResult("loaded")
		} catch (exception: Exception) {
			logger.error("체크아웃 상품 스냅샷 비동기 재적재에 실패했습니다. saleId={}", saleId, exception)
			recordRefreshResult("failed")
		}
	}

	private fun loadAndStore(saleId: Long, now: Instant): CheckoutProductSnapshot? {
		val snapshot = sourceQuery.findBySaleId(saleId) ?: return null
		try {
			val value = CachedCheckoutProductSnapshot(
				schemaVersion = SCHEMA_VERSION,
				saleId = snapshot.saleId,
				productName = snapshot.productName,
				representativeImagePath = snapshot.representativeImageObjectKey?.let { "/$it" },
				unitPrice = snapshot.unitPrice,
				freshUntil = now.plus(properties.freshTtl),
				staleUntil = now.plus(properties.freshTtl).plus(properties.staleTtl),
			)
			redisTemplate.opsForValue().set(keyFactory.snapshot(saleId), objectMapper.writeValueAsString(value), properties.physicalTtl)
		} catch (exception: Exception) {
			logger.error("체크아웃 상품 스냅샷 Redis 저장에 실패했습니다. saleId={}", saleId, exception)
		}
		return snapshot
	}

	private fun read(saleId: Long): CachedValue = try {
		val value = redisTemplate.opsForValue().get(keyFactory.snapshot(saleId)) ?: return CachedValue.Missing
		val cached = objectMapper.readValue(value, CachedCheckoutProductSnapshot::class.java)
		if (cached.schemaVersion != SCHEMA_VERSION || cached.saleId != saleId) return CachedValue.Missing
		val snapshot = CheckoutProductSnapshot(
			cached.saleId,
			cached.productName,
			cached.representativeImagePath?.removePrefix("/"),
			cached.unitPrice,
		)
		val now = Instant.now(clock)
		when {
			now.isBefore(cached.freshUntil) -> CachedValue.Fresh(snapshot)
			now.isBefore(cached.staleUntil) -> CachedValue.Stale(snapshot)
			else -> CachedValue.Expired
		}
	} catch (exception: Exception) {
		logger.error("체크아웃 상품 스냅샷 Redis 조회에 실패했습니다. saleId={}", saleId, exception)
		CachedValue.Missing
	}

	private fun recordCacheResult(result: String) =
		meterRegistry.counter("order.checkout.cache.result", "result", result).increment()

	private fun recordRefreshResult(result: String) =
		meterRegistry.counter("order.checkout.cache.refresh", "result", result).increment()

	private sealed interface CachedValue {
		data class Fresh(val snapshot: CheckoutProductSnapshot) : CachedValue
		data class Stale(val snapshot: CheckoutProductSnapshot) : CachedValue
		data object Missing : CachedValue
		data object Expired : CachedValue
	}

	private data class CachedCheckoutProductSnapshot(
		val schemaVersion: Int,
		val saleId: Long,
		val productName: String?,
		val representativeImagePath: String?,
		val unitPrice: Long,
		val freshUntil: Instant,
		val staleUntil: Instant,
	)

	private companion object {
		const val SCHEMA_VERSION = 1
		val logger = LoggerFactory.getLogger(RedisCheckoutProductSnapshotQuery::class.java)
	}
}
