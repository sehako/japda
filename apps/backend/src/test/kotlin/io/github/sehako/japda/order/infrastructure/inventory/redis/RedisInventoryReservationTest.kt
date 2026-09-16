package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.application.inventory.InventoryReservationResult
import io.github.sehako.japda.order.application.inventory.snapshot.InventorySnapshotResult
import io.github.sehako.japda.order.application.inventory.snapshot.InventorySnapshotService
import io.github.sehako.japda.order.infrastructure.inventory.config.RedisInventoryProperties
import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("동적 Redis 재고 선점")
class RedisInventoryReservationTest {
    private val connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(REDIS_PORT)).also { it.afterPropertiesSet() }
    private val redisTemplate = StringRedisTemplate(connectionFactory).also { it.afterPropertiesSet() }
    private val properties = RedisInventoryProperties(namespace = "reservation-test")
    private val keyFactory = RedisInventoryKeyFactory(properties.namespace)
    private val scripts = RedisInventoryScripts(redisTemplate, keyFactory, properties.stockTtl)
    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var snapshotService: InventorySnapshotService
    private lateinit var reservation: RedisInventoryReservation

    @BeforeEach
    fun Redis와_의존성을_초기화한다() {
        connectionFactory.connection.serverCommands().flushAll()
        snapshotService = mock(InventorySnapshotService::class.java)
        reservation = RedisInventoryReservation(scripts, snapshotService, properties, meterRegistry)
    }

    @Test
    fun `cache_miss이면_lock_소유자만_snapshot을_초기화하고_선점한다`() {
        `when`(snapshotService.read(10L)).thenReturn(InventorySnapshotResult.Available(5))

        val result = reservation.reserve(10L, 2)

        val reserved = assertIs<InventoryReservationResult.Reserved>(result)
        assertEquals(10L, reserved.token.saleId)
        assertEquals(2, reserved.token.quantity)
        assertEquals("3", redisTemplate.opsForHash<String, String>().get(keyFactory.stock(10L), "available"))
    }

    @Test
    fun `동시_cache_miss는_Redis_lock으로_snapshot_조회_하나만_허용한다`() {
        val snapshotStarted = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val snapshotReads = AtomicInteger()
        doAnswer {
            snapshotReads.incrementAndGet()
            snapshotStarted.countDown()
            releaseSnapshot.await(1, TimeUnit.SECONDS)
            InventorySnapshotResult.Available(2)
        }.`when`(snapshotService).read(11L)
        val executor = Executors.newFixedThreadPool(2)

        val first = executor.submit(Callable { reservation.reserve(11L, 1) })
        assertTrue(snapshotStarted.await(1, TimeUnit.SECONDS))
        val second = executor.submit(Callable { reservation.reserve(11L, 1) })
        releaseSnapshot.countDown()

        assertIs<InventoryReservationResult.Reserved>(first.get())
        assertIs<InventoryReservationResult.Reserved>(second.get())
        assertEquals(1, snapshotReads.get())
        executor.shutdownNow()
    }

    @Test
    fun `polling_interrupt는_flag를_복원하고_fallback한다`() {
        assertTrue(scripts.tryAcquireInitializationLock(12L, "another-owner", Duration.ofSeconds(3)))
        val executor = Executors.newSingleThreadExecutor()
        val interrupted = AtomicInteger()
        val outcome = AtomicReference<InventoryReservationResult>()
        val result = executor.submit(Callable {
            val value = reservation.reserve(12L, 1)
            outcome.set(value)
            if (Thread.currentThread().isInterrupted) interrupted.incrementAndGet()
            value
        })
        Thread.sleep(100)
        result.cancel(true)

        executor.shutdown()
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        assertEquals(InventoryReservationResult.Fallback, outcome.get())
        assertEquals(1, interrupted.get())
    }

    @Test
    fun `복원은_선점_수량을_한_번만_되돌린다`() {
        `when`(snapshotService.read(13L)).thenReturn(InventorySnapshotResult.Available(3))
        val token = assertIs<InventoryReservationResult.Reserved>(reservation.reserve(13L, 2)).token

        reservation.restore(token)
        reservation.restore(token)

        assertIs<InventoryReservationResult.Reserved>(reservation.reserve(13L, 3))
    }

    @Test
    fun `관측_지표는_식별자_없이_operation과_result만_태깅한다`() {
        `when`(snapshotService.read(14L)).thenReturn(InventorySnapshotResult.Available(0))

        assertEquals(InventoryReservationResult.Insufficient, reservation.reserve(14L, 1))

        assertEquals(
            1.0,
            meterRegistry.get("order.inventory.redis.operations")
                .tag("operation", "reserve")
                .tag("result", "insufficient")
                .counter().count(),
        )
        assertEquals(
            1L,
            meterRegistry.get("order.inventory.redis.duration")
                .tag("operation", "reserve")
                .tag("result", "insufficient")
                .timer().count(),
        )
        assertEquals(
            1.0,
            meterRegistry.get("order.inventory.redis.operations")
                .tag("operation", "initialize")
                .tag("result", "reserved")
                .counter().count(),
        )
        val allowedResults = setOf("reserved", "duplicate", "conflict", "insufficient", "fallback", "error")
        meterRegistry.meters
            .flatMap { it.id.tags }
            .filter { it.key == "result" }
            .forEach { assertTrue(it.value in allowedResults) }
    }

    @Test
    fun `선점_응답이_유실되면_같은_reservationId로_한_번_재실행해_token을_회수한다`() {
        val unreliableScripts = mock(RedisInventoryScripts::class.java)
        `when`(unreliableScripts.reserve(anyLong(), anyReservationId(), anyInt()))
            .thenThrow(IllegalStateException("응답 유실"))
            .thenReturn(RedisReserveScriptResult.Duplicate("generation-15"))
        val retryingReservation = RedisInventoryReservation(unreliableScripts, snapshotService, properties, meterRegistry)

        val result = assertIs<InventoryReservationResult.Reserved>(retryingReservation.reserve(15L, 2))

        val reservationId = ArgumentCaptor.forClass(String::class.java)
        verify(unreliableScripts, times(2)).reserve(
            org.mockito.ArgumentMatchers.eq(15L),
            captureReservationId(reservationId),
            org.mockito.ArgumentMatchers.eq(2),
        )
        assertEquals(listOf(result.token.reservationId, result.token.reservationId), reservationId.allValues)
    }

    @Test
    fun `선점_재실행도_실패하면_fallback한다`() {
        val unavailableScripts = mock(RedisInventoryScripts::class.java)
        `when`(unavailableScripts.reserve(anyLong(), anyReservationId(), anyInt()))
            .thenThrow(IllegalStateException("첫 번째 실패"), IllegalStateException("두 번째 실패"))
        val retryingReservation = RedisInventoryReservation(unavailableScripts, snapshotService, properties, meterRegistry)

        assertEquals(InventoryReservationResult.Fallback, retryingReservation.reserve(16L, 1))

        verify(unavailableScripts, times(2)).reserve(
            org.mockito.ArgumentMatchers.eq(16L),
            anyReservationId(),
            org.mockito.ArgumentMatchers.eq(1),
        )
    }

    private fun anyReservationId(): String {
        anyString()
        return ""
    }

    private fun captureReservationId(captor: ArgumentCaptor<String>): String {
        captor.capture()
        return ""
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
