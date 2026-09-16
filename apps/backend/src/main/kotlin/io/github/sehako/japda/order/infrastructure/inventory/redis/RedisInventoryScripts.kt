package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript

sealed interface RedisReserveScriptResult {
    data class Reserved(val generation: String) : RedisReserveScriptResult
    data class Duplicate(val generation: String) : RedisReserveScriptResult
    data object Insufficient : RedisReserveScriptResult
    data object NotInitialized : RedisReserveScriptResult
    data object Conflict : RedisReserveScriptResult
}

enum class RedisRestoreScriptResult {
    RESTORED,
    MISSING,
    STALE,
}

class RedisInventoryScripts(
    private val redisTemplate: StringRedisTemplate,
    private val keyFactory: RedisInventoryKeyFactory,
    private val stockTtl: Duration,
) {
    fun tryAcquireInitializationLock(saleId: Long, token: String, lockTtl: Duration): Boolean =
        redisTemplate.opsForValue().setIfAbsent(keyFactory.initializationLock(saleId), token, lockTtl) == true

    fun initialize(saleId: Long, token: String, generation: String, available: Int): Boolean {
        val result = redisTemplate.execute(
            INITIALIZE_SCRIPT,
            listOf(keyFactory.stock(saleId), keyFactory.initializationLock(saleId)),
            token,
            generation,
            available.toString(),
            stockTtl.toMillis().toString(),
        )
        return result == 1L
    }

    fun unlockInitialization(saleId: Long, token: String): Boolean =
        redisTemplate.execute(UNLOCK_SCRIPT, listOf(keyFactory.initializationLock(saleId)), token) == 1L

    fun stockExists(saleId: Long): Boolean = redisTemplate.hasKey(keyFactory.stock(saleId))

    fun reserve(saleId: Long, reservationId: String, quantity: Int): RedisReserveScriptResult {
        val result = redisTemplate.execute(
            RESERVE_SCRIPT,
            listOf(keyFactory.stock(saleId), keyFactory.reservation(saleId, reservationId)),
            saleId.toString(),
            quantity.toString(),
        ) ?: return RedisReserveScriptResult.NotInitialized
        val parts = result.split(RESULT_SEPARATOR, limit = 2)
        return when (parts[0]) {
            RESERVED -> RedisReserveScriptResult.Reserved(parts[1])
            DUPLICATE -> RedisReserveScriptResult.Duplicate(parts[1])
            INSUFFICIENT -> RedisReserveScriptResult.Insufficient
            CONFLICT -> RedisReserveScriptResult.Conflict
            else -> RedisReserveScriptResult.NotInitialized
        }
    }

    fun restore(saleId: Long, reservationId: String, generation: String, quantity: Int): RedisRestoreScriptResult {
        val result = redisTemplate.execute(
            RESTORE_SCRIPT,
            listOf(keyFactory.stock(saleId), keyFactory.reservation(saleId, reservationId)),
            generation,
            quantity.toString(),
        )
        return when (result) {
            1L -> RedisRestoreScriptResult.RESTORED
            0L -> RedisRestoreScriptResult.MISSING
            else -> RedisRestoreScriptResult.STALE
        }
    }

    fun invalidate(saleId: Long, reservationId: String, generation: String): Boolean =
        redisTemplate.execute(
            INVALIDATE_SCRIPT,
            listOf(keyFactory.stock(saleId), keyFactory.reservation(saleId, reservationId)),
            generation,
        ) == 1L

    private companion object {
        const val RESULT_SEPARATOR = "|"
        const val RESERVED = "RESERVED"
        const val DUPLICATE = "DUPLICATE"
        const val INSUFFICIENT = "INSUFFICIENT"
        const val CONFLICT = "CONFLICT"

        val INITIALIZE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'generation', ARGV[2], 'available', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        val UNLOCK_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """.trimIndent(),
            Long::class.java,
        )

        val RESERVE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 'NOT_INITIALIZED' end
            local generation = redis.call('HGET', KEYS[1], 'generation')
            local ttl = redis.call('PTTL', KEYS[1])
            if not generation or ttl <= 0 then return 'NOT_INITIALIZED' end
            if redis.call('EXISTS', KEYS[2]) == 1 then
                local sameSale = redis.call('HGET', KEYS[2], 'saleId') == ARGV[1]
                local sameGeneration = redis.call('HGET', KEYS[2], 'generation') == generation
                local sameQuantity = redis.call('HGET', KEYS[2], 'quantity') == ARGV[2]
                if sameSale and sameGeneration and sameQuantity then return 'DUPLICATE|' .. generation end
                return 'CONFLICT'
            end
            local available = tonumber(redis.call('HGET', KEYS[1], 'available'))
            local quantity = tonumber(ARGV[2])
            if not available then return 'NOT_INITIALIZED' end
            if available < quantity then return 'INSUFFICIENT' end
            redis.call('HINCRBY', KEYS[1], 'available', -quantity)
            redis.call('HSET', KEYS[2], 'saleId', ARGV[1], 'generation', generation, 'quantity', ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ttl)
            return 'RESERVED|' .. generation
            """.trimIndent(),
            String::class.java,
        )

        val RESTORE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('EXISTS', KEYS[2]) == 0 then return 0 end
            local reservationGeneration = redis.call('HGET', KEYS[2], 'generation')
            local reservationQuantity = redis.call('HGET', KEYS[2], 'quantity')
            local currentGeneration = redis.call('HGET', KEYS[1], 'generation')
            if reservationGeneration ~= ARGV[1] or reservationQuantity ~= ARGV[2] or currentGeneration ~= ARGV[1] then return -1 end
            redis.call('HINCRBY', KEYS[1], 'available', tonumber(ARGV[2]))
            redis.call('DEL', KEYS[2])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        val INVALIDATE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
}
