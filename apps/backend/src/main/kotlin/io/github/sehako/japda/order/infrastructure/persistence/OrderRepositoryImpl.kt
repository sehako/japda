package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
import java.util.UUID
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class OrderRepositoryImpl(
	private val orderJpaRepository: OrderJpaRepository,
) : OrderRepository {
	override fun findByBuyerIdAndIdempotencyKey(buyerId: Long, idempotencyKey: UUID): Order? =
		orderJpaRepository.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey)

	override fun findByPaymentOrderId(paymentOrderId: String): Order? = orderJpaRepository.findByPaymentOrderId(paymentOrderId)

	override fun findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId: String, buyerId: Long): Long? =
		orderJpaRepository.findSaleIdByPaymentOrderIdAndBuyerId(paymentOrderId, buyerId)

	override fun findById(id: Long): Order? = orderJpaRepository.findById(id).orElse(null)

	override fun findSaleIdById(id: Long): Long? = orderJpaRepository.findSaleIdById(id)

	override fun markPaidIfPending(orderId: Long): Boolean = orderJpaRepository.markPaidIfPending(orderId) == 1

	override fun save(order: Order): Order = try {
		orderJpaRepository.saveAndFlush(order)
	} catch (exception: DataIntegrityViolationException) {
		val constraintName = generateSequence<Throwable>(exception) { it.cause }
			.filterIsInstance<ConstraintViolationException>()
			.firstOrNull()
			?.constraintName
		if (constraintName == IDEMPOTENCY_UNIQUE_CONSTRAINT) {
			throw OrderIdempotencyPersistenceException(exception)
		}
		throw exception
	}

	private companion object {
		const val IDEMPOTENCY_UNIQUE_CONSTRAINT = "orders_buyer_id_idempotency_key_unique"
	}
}
