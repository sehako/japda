package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Redis 재고 Lua script")
class RedisInventoryScriptsTest {
    private val connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(REDIS_PORT)).also { it.afterPropertiesSet() }
    private val redisTemplate = StringRedisTemplate(connectionFactory).also { it.afterPropertiesSet() }
    private val keyFactory = RedisInventoryKeyFactory("test")
    private val scripts = RedisInventoryScripts(redisTemplate, keyFactory, Duration.ofSeconds(30))

    @BeforeEach
    fun Redis를_초기화한다() {
        connectionFactory.connection.serverCommands().flushAll()
    }

    @Test
    fun `lock_token_소유자만_재고를_초기화하고_해제한다`() {
        assertTrue(scripts.tryAcquireInitializationLock(1L, "owner", Duration.ofSeconds(3)))
        assertFalse(scripts.initialize(1L, "other", "generation-1", 5))
        assertFalse(scripts.unlockInitialization(1L, "other"))

        assertTrue(scripts.initialize(1L, "owner", "generation-1", 5))
        assertTrue(scripts.unlockInitialization(1L, "owner"))
        assertEquals("5", redisTemplate.opsForHash<String, String>().get(keyFactory.stock(1L), "available"))
    }

    @Test
    fun `선점은_결과를_구분하고_재고_TTL을_연장하지_않는다`() {
        initialize(saleId = 2L, generation = "generation-2", available = 3)
        val stockKey = keyFactory.stock(2L)
        val ttlBefore = requireNotNull(redisTemplate.getExpire(stockKey))

        assertEquals(RedisReserveScriptResult.Reserved("generation-2"), scripts.reserve(2L, "reservation-1", 2))
        assertEquals(RedisReserveScriptResult.Duplicate("generation-2"), scripts.reserve(2L, "reservation-1", 2))
        assertEquals(RedisReserveScriptResult.Conflict, scripts.reserve(2L, "reservation-1", 1))
        assertEquals(RedisReserveScriptResult.Insufficient, scripts.reserve(2L, "reservation-2", 2))
        assertTrue(requireNotNull(redisTemplate.getExpire(stockKey)) <= ttlBefore)
        assertEquals(RedisReserveScriptResult.NotInitialized, scripts.reserve(999L, "reservation-3", 1))
    }

    @Test
    fun `같은_sale_key의_예약_saleId가_다르면_conflict를_반환한다`() {
        initialize(saleId = 21L, generation = "generation-21", available = 3)
        assertIs<RedisReserveScriptResult.Reserved>(scripts.reserve(21L, "reservation-21", 1))
        redisTemplate.opsForHash<String, String>().put(keyFactory.reservation(21L, "reservation-21"), "saleId", "999")

        assertEquals(RedisReserveScriptResult.Conflict, scripts.reserve(21L, "reservation-21", 1))
    }

    @Test
    fun `병렬_선점_합계는_초기_재고를_초과하지_않는다`() {
        initialize(saleId = 3L, generation = "generation-3", available = 5)
        val executor = Executors.newFixedThreadPool(10)

        val results = try {
            executor.invokeAll((1..20).map { index -> Callable { scripts.reserve(3L, "reservation-$index", 1) } })
                .map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(5, results.count { it is RedisReserveScriptResult.Reserved })
        assertEquals(15, results.count { it == RedisReserveScriptResult.Insufficient })
        assertEquals("0", redisTemplate.opsForHash<String, String>().get(keyFactory.stock(3L), "available"))
    }

    @Test
    fun `복원과_폐기는_현재_generation만_변경한다`() {
        initialize(saleId = 4L, generation = "old", available = 3)
        assertIs<RedisReserveScriptResult.Reserved>(scripts.reserve(4L, "old-reservation", 2))
        redisTemplate.opsForHash<String, String>().putAll(keyFactory.stock(4L), mapOf("generation" to "new", "available" to "7"))

        assertEquals(RedisRestoreScriptResult.STALE, scripts.restore(4L, "old-reservation", "old", 2))
        assertEquals("7", redisTemplate.opsForHash<String, String>().get(keyFactory.stock(4L), "available"))
        assertFalse(scripts.invalidate(4L, "old-reservation", "old"))
        assertTrue(redisTemplate.hasKey(keyFactory.stock(4L)))

        assertTrue(scripts.invalidate(4L, "old-reservation", "new"))
        assertFalse(redisTemplate.hasKey(keyFactory.stock(4L)))
    }

    private fun initialize(saleId: Long, generation: String, available: Int) {
        assertTrue(scripts.tryAcquireInitializationLock(saleId, "owner", Duration.ofSeconds(3)))
        assertTrue(scripts.initialize(saleId, "owner", generation, available))
    }

    companion object {
        private const val REDIS_PORT = 6379

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7.4-alpine").withExposedPorts(REDIS_PORT)

    }

    @AfterEach
    fun 연결을_종료한다() {
        connectionFactory.destroy()
    }
}
