package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.application.inventory.SoldOutInventoryMarker
import io.github.sehako.japda.order.infrastructure.inventory.config.RedisInventoryProperties
import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate

class RedisSoldOutInventoryMarker(
	private val redisTemplate: StringRedisTemplate,
	private val keyFactory: RedisInventoryKeyFactory,
	private val properties: RedisInventoryProperties,
	private val meterRegistry: MeterRegistry,
) : SoldOutInventoryMarker {
	override fun isSoldOut(saleId: Long): Boolean {
		val sample = Timer.start(meterRegistry)
		var result = RESULT_ERROR
		return try {
			val soldOut = redisTemplate.hasKey(keyFactory.soldOut(saleId)) == true
			result = if (soldOut) RESULT_HIT else RESULT_MISS
			soldOut
		} catch (exception: Exception) {
			result = handleFailure(OPERATION_CHECK, saleId, exception)
			false
		} finally {
			record(OPERATION_CHECK, result, sample)
		}
	}

	override fun markSoldOut(saleId: Long) {
		val sample = Timer.start(meterRegistry)
		var result = RESULT_ERROR
		try {
			val marked = redisTemplate.opsForValue().setIfAbsent(
				keyFactory.soldOut(saleId),
				MARKER_VALUE,
				properties.soldOutTtl,
			) == true
			result = if (marked) RESULT_MARKED else RESULT_DUPLICATE
		} catch (exception: Exception) {
			result = handleFailure(OPERATION_MARK, saleId, exception)
		} finally {
			record(OPERATION_MARK, result, sample)
		}
	}

	private fun handleFailure(operation: String, saleId: Long, exception: Exception): String {
		val interrupted = Thread.currentThread().isInterrupted || exception.hasInterruptedCause()
		if (interrupted) {
			Thread.currentThread().interrupt()
		}
		logger.error("Redis 품절 마커 연산에 실패했습니다. operation={}, saleId={}", operation, saleId, exception)
		return if (interrupted) RESULT_FALLBACK else RESULT_ERROR
	}

	private fun record(operation: String, result: String, sample: Timer.Sample) {
		meterRegistry.counter(METRIC_OPERATIONS, TAG_OPERATION, operation, TAG_RESULT, result).increment()
		sample.stop(meterRegistry.timer(METRIC_DURATION, TAG_OPERATION, operation, TAG_RESULT, result))
	}

	private fun Throwable.hasInterruptedCause(): Boolean {
		var current: Throwable? = this
		while (current != null) {
			if (current is InterruptedException) {
				return true
			}
			current = current.cause
		}
		return false
	}

	private companion object {
		val logger = LoggerFactory.getLogger(RedisSoldOutInventoryMarker::class.java)
		const val MARKER_VALUE = "1"
		const val METRIC_OPERATIONS = "order.inventory.redis.operations"
		const val METRIC_DURATION = "order.inventory.redis.duration"
		const val TAG_OPERATION = "operation"
		const val TAG_RESULT = "result"
		const val OPERATION_CHECK = "check"
		const val OPERATION_MARK = "mark"
		const val RESULT_HIT = "hit"
		const val RESULT_MISS = "miss"
		const val RESULT_MARKED = "marked"
		const val RESULT_DUPLICATE = "duplicate"
		const val RESULT_FALLBACK = "fallback"
		const val RESULT_ERROR = "error"
	}
}
