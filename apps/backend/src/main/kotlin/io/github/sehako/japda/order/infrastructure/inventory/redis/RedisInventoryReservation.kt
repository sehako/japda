package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.application.inventory.InventoryReservation
import io.github.sehako.japda.order.application.inventory.InventoryReservationResult
import io.github.sehako.japda.order.application.inventory.InventoryReservationToken
import io.github.sehako.japda.order.application.inventory.snapshot.InventorySnapshotResult
import io.github.sehako.japda.order.application.inventory.snapshot.InventorySnapshotService
import io.github.sehako.japda.order.infrastructure.inventory.config.RedisInventoryProperties
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.UUID
import org.slf4j.LoggerFactory

class RedisInventoryReservation(
    private val scripts: RedisInventoryScripts,
    private val snapshotService: InventorySnapshotService,
    private val properties: RedisInventoryProperties,
    private val meterRegistry: MeterRegistry,
) : InventoryReservation {
    override fun reserve(saleId: Long, quantity: Int): InventoryReservationResult {
        val sample = Timer.start(meterRegistry)
        val reservationId = UUID.randomUUID().toString()
        var metricResult = RESULT_FALLBACK
        return try {
            var result = reserveWithRetry(saleId, reservationId, quantity)
            if (result == RedisReserveScriptResult.NotInitialized) {
                if (!initializeOrAwait(saleId)) {
                    return InventoryReservationResult.Fallback
                }
                result = reserveWithRetry(saleId, reservationId, quantity)
            }
            when (result) {
                is RedisReserveScriptResult.Reserved -> {
                    metricResult = RESULT_RESERVED
                    reserved(reservationId, saleId, result.generation, quantity)
                }
                is RedisReserveScriptResult.Duplicate -> {
                    metricResult = RESULT_DUPLICATE
                    reserved(reservationId, saleId, result.generation, quantity)
                }
                RedisReserveScriptResult.Insufficient -> {
                    metricResult = RESULT_INSUFFICIENT
                    InventoryReservationResult.Insufficient
                }
                RedisReserveScriptResult.Conflict -> {
                    metricResult = RESULT_CONFLICT
                    InventoryReservationResult.Fallback
                }
                RedisReserveScriptResult.NotInitialized -> InventoryReservationResult.Fallback
            }
        } catch (exception: Exception) {
            metricResult = RESULT_ERROR
            logger.error("Redis 재고 선점에 실패했습니다. saleId={}, reservationId={}", saleId, reservationId, exception)
            InventoryReservationResult.Fallback
        } finally {
            record(OPERATION_RESERVE, metricResult, sample)
        }
    }

    override fun restore(token: InventoryReservationToken) {
        observeCompensation(OPERATION_RESTORE, token) {
            when (scripts.restore(token.saleId, token.reservationId, token.generation, token.quantity)) {
                RedisRestoreScriptResult.RESTORED -> RESULT_RESERVED
                RedisRestoreScriptResult.MISSING -> RESULT_DUPLICATE
                RedisRestoreScriptResult.STALE -> RESULT_CONFLICT
            }
        }
    }

    override fun invalidate(token: InventoryReservationToken) {
        observeCompensation(OPERATION_INVALIDATE, token) {
            if (scripts.invalidate(token.saleId, token.reservationId, token.generation)) RESULT_RESERVED else RESULT_CONFLICT
        }
    }

    private fun reserveWithRetry(saleId: Long, reservationId: String, quantity: Int): RedisReserveScriptResult =
        try {
            scripts.reserve(saleId, reservationId, quantity)
        } catch (exception: Exception) {
            if (Thread.currentThread().isInterrupted || exception.hasInterruptedCause()) {
                Thread.currentThread().interrupt()
                throw exception
            }
            logger.error(
                "Redis 재고 선점 응답을 확인하지 못해 같은 예약 식별자로 재실행합니다. saleId={}, reservationId={}",
                saleId,
                reservationId,
                exception,
            )
            scripts.reserve(saleId, reservationId, quantity)
        }

    private fun initializeOrAwait(saleId: Long): Boolean {
        val sample = Timer.start(meterRegistry)
        val lockToken = UUID.randomUUID().toString()
        var metricResult = RESULT_FALLBACK
        return try {
            if (!scripts.tryAcquireInitializationLock(saleId, lockToken, properties.lockTtl)) {
                val initialized = awaitStock(saleId)
                metricResult = if (initialized) RESULT_DUPLICATE else RESULT_FALLBACK
                initialized
            } else {
                try {
                    if (scripts.stockExists(saleId)) {
                        metricResult = RESULT_DUPLICATE
                        true
                    } else {
                        when (val snapshot = snapshotService.read(saleId)) {
                            is InventorySnapshotResult.Available -> {
                                val initialized = scripts.initialize(
                                    saleId,
                                    lockToken,
                                    UUID.randomUUID().toString(),
                                    snapshot.quantity,
                                )
                                metricResult = if (initialized) RESULT_RESERVED else RESULT_CONFLICT
                                initialized
                            }
                            InventorySnapshotResult.InitializationFailed -> false
                        }
                    }
                } finally {
                    try {
                        scripts.unlockInitialization(saleId, lockToken)
                    } catch (exception: Exception) {
                        metricResult = RESULT_ERROR
                        logger.error("Redis 재고 초기화 lock 해제에 실패했습니다. saleId={}", saleId, exception)
                    }
                }
            }
        } catch (exception: Exception) {
            metricResult = RESULT_ERROR
            throw exception
        } finally {
            record(OPERATION_INITIALIZE, metricResult, sample)
        }
    }

    private fun awaitStock(saleId: Long): Boolean {
        val deadline = System.nanoTime() + properties.pollingTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (scripts.stockExists(saleId)) {
                return true
            }
            try {
                Thread.sleep(properties.pollingInterval.toMillis())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return scripts.stockExists(saleId)
    }

    private fun observeCompensation(
        operation: String,
        token: InventoryReservationToken,
        action: () -> String,
    ) {
        val sample = Timer.start(meterRegistry)
        var metricResult = RESULT_ERROR
        try {
            metricResult = action()
        } catch (exception: Exception) {
            logger.error(
                "Redis 재고 보상에 실패했습니다. operation={}, saleId={}, reservationId={}",
                operation,
                token.saleId,
                token.reservationId,
                exception,
            )
        } finally {
            record(operation, metricResult, sample)
        }
    }

    private fun record(operation: String, result: String, sample: Timer.Sample) {
        meterRegistry.counter(METRIC_OPERATIONS, TAG_OPERATION, operation, TAG_RESULT, result).increment()
        sample.stop(meterRegistry.timer(METRIC_DURATION, TAG_OPERATION, operation, TAG_RESULT, result))
    }

    private fun reserved(
        reservationId: String,
        saleId: Long,
        generation: String,
        quantity: Int,
    ) = InventoryReservationResult.Reserved(
        InventoryReservationToken(reservationId, saleId, generation, quantity),
    )

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
        val logger = LoggerFactory.getLogger(RedisInventoryReservation::class.java)
        const val METRIC_OPERATIONS = "order.inventory.redis.operations"
        const val METRIC_DURATION = "order.inventory.redis.duration"
        const val TAG_OPERATION = "operation"
        const val TAG_RESULT = "result"
        const val OPERATION_RESERVE = "reserve"
        const val OPERATION_INITIALIZE = "initialize"
        const val OPERATION_RESTORE = "restore"
        const val OPERATION_INVALIDATE = "invalidate"
        const val RESULT_RESERVED = "reserved"
        const val RESULT_DUPLICATE = "duplicate"
        const val RESULT_INSUFFICIENT = "insufficient"
        const val RESULT_CONFLICT = "conflict"
        const val RESULT_FALLBACK = "fallback"
        const val RESULT_ERROR = "error"
    }
}
