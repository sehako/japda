package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.inventory.InventoryReservation
import io.github.sehako.japda.order.application.inventory.InventoryReservationResult
import io.github.sehako.japda.order.application.inventory.InventoryReservationToken
import io.github.sehako.japda.order.application.response.OrderResponse
import io.github.sehako.japda.order.application.response.toResponse
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class OrderService(
	private val orderRepository: OrderRepository,
	private val transactionService: OrderCreationTransactionService,
	private val inventoryReservation: InventoryReservation,
) {
	fun create(dto: CreateOrderDto): OrderResponse {
		val request = dto.toDomainRequest()
		orderRepository.findByBuyerIdAndIdempotencyKey(request.buyerId, request.idempotencyKey)?.let {
			return resolveExisting(it, request)
		}

		val reservationToken = when (val result = reserve(request.saleId, request.quantity)) {
			is InventoryReservationResult.Reserved -> result.token
			InventoryReservationResult.Insufficient -> throw OrderException(OrderErrorCode.QUANTITY_UNAVAILABLE)
			InventoryReservationResult.Fallback -> null
		}

		val creationResult = try {
			try {
				transactionService.create(request)
			} catch (_: OrderIdempotencyPersistenceException) {
				transactionService.recoverIdempotentRequest(request)
			}
		} catch (exception: Exception) {
			reservationToken?.let { compensateFailure(it, exception) }
			throw exception
		}

		if (!creationResult.created) reservationToken?.let(::restore)
		return creationResult.response
	}

	private fun reserve(saleId: Long, quantity: Int): InventoryReservationResult = try {
		inventoryReservation.reserve(saleId, quantity)
	} catch (exception: Exception) {
		if (exception is InterruptedException) Thread.currentThread().interrupt()
		logger.error("Redis 재고 선점 실패로 DB 경로로 우회합니다. saleId={}", saleId, exception)
		InventoryReservationResult.Fallback
	}

	private fun compensateFailure(token: InventoryReservationToken, exception: Exception) {
		if (exception is OrderException && exception.errorCode == OrderErrorCode.QUANTITY_UNAVAILABLE) {
			invalidate(token)
		} else {
			restore(token)
		}
	}

	private fun restore(token: InventoryReservationToken) {
		try {
			inventoryReservation.restore(token)
		} catch (exception: Exception) {
			logger.error(
				"Redis 재고 예약 복원에 실패했습니다. saleId={}, reservationId={}",
				token.saleId,
				token.reservationId,
				exception,
			)
		}
	}

	private fun invalidate(token: InventoryReservationToken) {
		try {
			inventoryReservation.invalidate(token)
		} catch (exception: Exception) {
			logger.error(
				"Redis 재고 세대 폐기에 실패했습니다. saleId={}, reservationId={}",
				token.saleId,
				token.reservationId,
				exception,
			)
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
