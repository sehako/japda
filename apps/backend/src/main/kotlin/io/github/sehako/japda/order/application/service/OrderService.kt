package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.response.OrderResponse
import io.github.sehako.japda.order.application.response.toResponse
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class OrderService(
	private val orderRepository: OrderRepository,
	private val transactionService: OrderCreationTransactionService,
) {
	fun create(dto: CreateOrderDto): OrderResponse {
		val request = dto.toDomainRequest()
		orderRepository.findByBuyerIdAndIdempotencyKey(request.buyerId, request.idempotencyKey)?.let {
			return resolveExisting(it, request)
		}

		return try {
			try {
				transactionService.create(request, UUID.randomUUID())
			} catch (_: OrderIdempotencyPersistenceException) {
				transactionService.recoverIdempotentRequest(request)
			}.response
		} catch (_: OrderInventoryInsufficientException) {
			throw OrderException(OrderErrorCode.QUANTITY_UNAVAILABLE)
		}
	}
}

internal fun resolveExisting(order: Order, request: OrderRequest): OrderResponse {
	if (!order.matches(request)) throw OrderException(OrderErrorCode.IDEMPOTENCY_CONFLICT)
	return order.toResponse()
}
