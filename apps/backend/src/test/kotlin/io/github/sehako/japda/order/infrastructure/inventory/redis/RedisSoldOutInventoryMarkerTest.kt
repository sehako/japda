package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.infrastructure.inventory.config.RedisInventoryProperties
import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Redis 품절 마커")
class RedisSoldOutInventoryMarkerTest {
    private val connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(REDIS_PORT)).also { it.afterPropertiesSet() }
    private val redisTemplate = StringRedisTemplate(connectionFactory).also { it.afterPropertiesSet() }
    private val properties = RedisInventoryProperties(namespace = "sold-out-test", soldOutTtl = Duration.ofSeconds(3))
    private val keyFactory = RedisInventoryKeyFactory(properties.namespace)
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var marker: RedisSoldOutInventoryMarker

    @BeforeEach
    fun Redis와_관측_정보를_초기화한다() {
        connectionFactory.connection.serverCommands().flushAll()
        meterRegistry = SimpleMeterRegistry()
        marker = RedisSoldOutInventoryMarker(redisTemplate, keyFactory, properties, meterRegistry)
    }

    @Test
    fun `마커가_없으면_miss를_반환한다`() {
        assertFalse(marker.isSoldOut(10L))

        assertMetric("check", "miss", 1.0)
    }

    @Test
    fun `품절을_기록하면_고정_TTL의_마커를_조회한다`() {
        marker.markSoldOut(11L)

        assertTrue(marker.isSoldOut(11L))
        assertEquals("1", redisTemplate.opsForValue().get(keyFactory.soldOut(11L)))
        val remainingMillis = redisTemplate.getExpire(keyFactory.soldOut(11L), TimeUnit.MILLISECONDS)
        assertTrue(remainingMillis in 1..properties.soldOutTtl.toMillis())
        assertMetric("mark", "marked", 1.0)
        assertMetric("check", "hit", 1.0)
    }

    @Test
    fun `중복_기록은_마커_TTL을_연장하지_않는다`() {
        marker.markSoldOut(12L)
        Thread.sleep(100)
        val beforeDuplicate = redisTemplate.getExpire(keyFactory.soldOut(12L), TimeUnit.MILLISECONDS)

        marker.markSoldOut(12L)

        val afterDuplicate = redisTemplate.getExpire(keyFactory.soldOut(12L), TimeUnit.MILLISECONDS)
        assertTrue(afterDuplicate <= beforeDuplicate)
        assertMetric("mark", "duplicate", 1.0)
    }

    @Test
    fun `조회_오류는_error로_관측하고_miss로_우회한다`() {
        val failingTemplate = mock(StringRedisTemplate::class.java)
        `when`(failingTemplate.hasKey(keyFactory.soldOut(13L))).thenThrow(IllegalStateException("Redis 조회 실패"))
        val failingMarker = RedisSoldOutInventoryMarker(failingTemplate, keyFactory, properties, meterRegistry)

        assertFalse(failingMarker.isSoldOut(13L))

        assertMetric("check", "error", 1.0)
    }

    @Test
    fun `interrupt가_포함된_조회_오류는_flag를_복원하고_fallback한다`() {
        val failingTemplate = mock(StringRedisTemplate::class.java)
        `when`(failingTemplate.hasKey(keyFactory.soldOut(14L)))
            .thenThrow(IllegalStateException("Redis 조회 interrupt", InterruptedException("interrupt")))
        val failingMarker = RedisSoldOutInventoryMarker(failingTemplate, keyFactory, properties, meterRegistry)

        try {
            assertFalse(failingMarker.isSoldOut(14L))
            assertTrue(Thread.currentThread().isInterrupted)
            assertMetric("check", "fallback", 1.0)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `기록_오류는_error로_관측하고_예외를_전파하지_않는다`() {
        val failingTemplate = mock(StringRedisTemplate::class.java)
        `when`(failingTemplate.opsForValue()).thenThrow(IllegalStateException("Redis 기록 실패"))
        val failingMarker = RedisSoldOutInventoryMarker(failingTemplate, keyFactory, properties, meterRegistry)

        failingMarker.markSoldOut(15L)

        assertMetric("mark", "error", 1.0)
    }

    private fun assertMetric(operation: String, result: String, expectedCount: Double) {
        assertEquals(
            expectedCount,
            meterRegistry.get("order.inventory.redis.operations")
                .tag("operation", operation)
                .tag("result", result)
                .counter().count(),
        )
        assertEquals(
            expectedCount.toLong(),
            meterRegistry.get("order.inventory.redis.duration")
                .tag("operation", operation)
                .tag("result", result)
                .timer().count(),
        )
    }

    @AfterEach
    fun 연결을_종료한다() {
        connectionFactory.destroy()
    }

    companion object {
        private const val REDIS_PORT = 6379

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7.4-alpine").withExposedPorts(REDIS_PORT)
    }
}
