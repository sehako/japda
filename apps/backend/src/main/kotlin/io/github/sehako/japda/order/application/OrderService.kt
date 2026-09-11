package io.github.sehako.japda.order.application

import io.github.sehako.japda.order.domain.Order
import io.github.sehako.japda.order.domain.OrderRepository
import io.github.sehako.japda.order.domain.OrderRequest
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
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
			transactionService.create(request)
		} catch (_: OrderIdempotencyPersistenceException) {
			transactionService.recoverIdempotentRequest(request)
		}
	}
}

internal fun resolveExisting(order: Order, request: OrderRequest): OrderResponse {
	if (!order.matches(request)) throw OrderException(OrderErrorCode.IDEMPOTENCY_CONFLICT)
	return order.toResponse()
}
