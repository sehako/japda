package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.application.response.OrderResponse
import io.github.sehako.japda.order.application.response.toResponse
import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class OrderCreationTransactionService(
	private val orderRepository: OrderRepository,
	private val saleRepository: SaleRepository,
	private val productRepository: ProductRepository,
	private val reservationRepository: InventoryReservationRepository,
	private val counterRepository: SaleInventoryCounterRepository,
	private val expiredReservationReleaseService: ExpiredInventoryReservationReleaseService,
	private val clock: Clock,
) {
	@Transactional
	fun create(request: OrderRequest, reservationId: UUID): OrderCreationResult {
		val sale = saleRepository.findById(request.saleId)
			?: throw OrderException(OrderErrorCode.SALE_NOT_FOUND)
		orderRepository.findByBuyerIdAndIdempotencyKey(request.buyerId, request.idempotencyKey)?.let {
			return OrderCreationResult(resolveExisting(it, request), created = false)
		}

		val now = clock.instant()
		if (now < sale.startsAt || now >= sale.endsAt) throw OrderException(OrderErrorCode.SALE_NOT_OPEN)
		val product = checkNotNull(productRepository.findById(sale.productId)) {
			"판매 일정의 상품을 찾을 수 없습니다."
		}
		expiredReservationReleaseService.releaseExpired(request.saleId, now)
		val order = orderRepository.save(Order.create(request, product.name, sale.price, now))
		when (counterRepository.reserve(request.saleId, request.quantity, now)) {
			SaleInventoryReserveResult.ACQUIRED -> Unit
			SaleInventoryReserveResult.INSUFFICIENT -> throw OrderException(OrderErrorCode.QUANTITY_UNAVAILABLE)
			SaleInventoryReserveResult.MISSING_COUNTER -> {
				logger.error("판매 일정의 재고 카운터를 찾을 수 없습니다. saleId={}", request.saleId)
				throw IllegalStateException("판매 일정의 재고 카운터를 찾을 수 없습니다.")
			}
		}
		reservationRepository.save(
			InventoryReservation.reserve(
				id = reservationId,
				saleId = request.saleId,
				orderId = requireNotNull(order.id),
				quantity = request.quantity,
				expiresAt = order.expiresAt,
				createdAt = now,
			),
		)
		return OrderCreationResult(
			response = order.toResponse(),
			created = true,
		)
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	fun recoverIdempotentRequest(request: OrderRequest): OrderCreationResult {
		val order = orderRepository.findByBuyerIdAndIdempotencyKey(request.buyerId, request.idempotencyKey)
			?: throw IllegalStateException("멱등성 충돌 주문을 찾을 수 없습니다.")
		return OrderCreationResult(resolveExisting(order, request), created = false)
	}

	private companion object {
		val logger = LoggerFactory.getLogger(OrderCreationTransactionService::class.java)
	}
}

data class OrderCreationResult(
	val response: OrderResponse,
	val created: Boolean,
)
