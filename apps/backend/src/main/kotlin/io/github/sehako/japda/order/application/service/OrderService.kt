package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.inventory.SoldOutInventoryMarker
import io.github.sehako.japda.order.application.response.OrderResponse
import io.github.sehako.japda.order.application.response.toResponse
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class OrderService(
	private val orderRepository: OrderRepository,
	private val transactionService: OrderCreationTransactionService,
	private val soldOutInventoryMarker: SoldOutInventoryMarker,
) {
	fun create(dto: CreateOrderDto): OrderResponse {
		val request = dto.toDomainRequest()
		orderRepository.findByBuyerIdAndIdempotencyKey(request.buyerId, request.idempotencyKey)?.let {
			return resolveExisting(it, request)
		}

		if (isSoldOut(request.saleId)) throw OrderException(OrderErrorCode.QUANTITY_UNAVAILABLE)

		return try {
			try {
				transactionService.create(request, UUID.randomUUID())
			} catch (_: OrderIdempotencyPersistenceException) {
				transactionService.recoverIdempotentRequest(request)
			}.response
		} catch (exception: OrderInventoryInsufficientException) {
			if (exception.remainingQuantity == 0) markSoldOut(request.saleId)
			throw OrderException(OrderErrorCode.QUANTITY_UNAVAILABLE)
		}
	}

	private fun isSoldOut(saleId: Long): Boolean = try {
		soldOutInventoryMarker.isSoldOut(saleId)
	} catch (exception: Exception) {
		if (exception is InterruptedException) Thread.currentThread().interrupt()
		logger.error("Redis 품절 마커 조회 실패로 DB 경로로 우회합니다. saleId={}", saleId, exception)
		false
	}

	private fun markSoldOut(saleId: Long) {
		try {
			soldOutInventoryMarker.markSoldOut(saleId)
		} catch (exception: Exception) {
			if (exception is InterruptedException) Thread.currentThread().interrupt()
			logger.error("Redis 품절 마커 기록에 실패했습니다. saleId={}", saleId, exception)
		}
	}

	private companion object {
		val logger = LoggerFactory.getLogger(OrderService::class.java)
	}
}

internal fun resolveExisting(order: Order, request: OrderRequest): OrderResponse {
	if (!order.matches(request)) throw OrderException(OrderErrorCode.IDEMPOTENCY_CONFLICT)
	return order.toResponse()
}
