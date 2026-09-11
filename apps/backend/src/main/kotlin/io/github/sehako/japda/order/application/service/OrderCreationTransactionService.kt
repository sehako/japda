package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.response.OrderResponse
import io.github.sehako.japda.order.application.response.toResponse
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class OrderCreationTransactionService(
	private val orderRepository: OrderRepository,
	private val saleRepository: SaleRepository,
	private val productRepository: ProductRepository,
	private val clock: Clock,
) {
	@Transactional
	fun create(request: OrderRequest): OrderResponse {
		val sale = saleRepository.findByIdForUpdate(request.saleId)
			?: throw OrderException(OrderErrorCode.SALE_NOT_FOUND)
		orderRepository.findByBuyerIdAndIdempotencyKey(request.buyerId, request.idempotencyKey)?.let {
			return resolveExisting(it, request)
		}

		val now = clock.instant()
		if (now < sale.startsAt || now >= sale.endsAt) throw OrderException(OrderErrorCode.SALE_NOT_OPEN)
		val reservedQuantity = orderRepository.sumActiveReservedQuantity(request.saleId, now)
		if (sale.quantity.toLong() - reservedQuantity < request.quantity.toLong()) {
			throw OrderException(OrderErrorCode.QUANTITY_UNAVAILABLE)
		}
		val product = checkNotNull(productRepository.findById(sale.productId)) {
			"판매 일정의 상품을 찾을 수 없습니다."
		}
		return orderRepository.save(Order.create(request, product.name, sale.price, now)).toResponse()
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	fun recoverIdempotentRequest(request: OrderRequest): OrderResponse {
		val order = orderRepository.findByBuyerIdAndIdempotencyKey(request.buyerId, request.idempotencyKey)
			?: throw IllegalStateException("멱등성 충돌 주문을 찾을 수 없습니다.")
		return resolveExisting(order, request)
	}
}
