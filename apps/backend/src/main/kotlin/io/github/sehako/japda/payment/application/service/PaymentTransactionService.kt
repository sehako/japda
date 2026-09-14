package io.github.sehako.japda.payment.application.service

import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderStatus
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import io.github.sehako.japda.payment.application.client.TossPaymentStatuses
import io.github.sehako.japda.payment.application.response.PaymentResponse
import io.github.sehako.japda.payment.domain.model.Payment
import io.github.sehako.japda.payment.domain.model.PaymentStatus
import io.github.sehako.japda.payment.domain.repository.PaymentRepository
import io.github.sehako.japda.payment.exception.PaymentErrorCode
import io.github.sehako.japda.payment.exception.PaymentException
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentTransactionService(
	private val orders: OrderRepository,
	private val payments: PaymentRepository,
	private val sales: SaleRepository,
	private val clock: Clock,
	@Value("\${payment.reconcile-interval:PT30S}") private val reconcileInterval: Duration,
) {
	@Transactional
	fun prepare(buyerId: Long, orderId: String, paymentKey: String, amount: Long): PreparedPayment {
		val saleId = orders.findSaleIdByPaymentOrderIdAndBuyerId(orderId, buyerId)
			?: throw PaymentException(PaymentErrorCode.ORDER_NOT_FOUND)
		sales.findByIdForUpdate(saleId) ?: throw PaymentException(PaymentErrorCode.ORDER_NOT_FOUND)
		val order = ownedOrder(buyerId, orderId)
		if (amount != order.totalPrice) throw PaymentException(PaymentErrorCode.AMOUNT_MISMATCH)
		val existing = payments.findByOrderId(requireNotNull(order.id))
		if (existing != null) {
			if (existing.paymentKey != paymentKey) throw PaymentException(PaymentErrorCode.KEY_CONFLICT)
			return when (existing.status) {
				PaymentStatus.APPROVED -> PreparedPayment.Completed(response(order, existing))
				PaymentStatus.CONFIRMING -> throw PaymentException(PaymentErrorCode.IN_PROGRESS)
				PaymentStatus.FAILED -> throw PaymentException(PaymentErrorCode.FAILED)
				PaymentStatus.REVIEW_REQUIRED -> throw PaymentException(PaymentErrorCode.REVIEW_REQUIRED)
			}
		}
		if (order.status != OrderStatus.PENDING_PAYMENT || !clock.instant().isBefore(order.expiresAt)) {
			throw PaymentException(PaymentErrorCode.ORDER_EXPIRED)
		}
		val payment = Payment.create(requireNotNull(order.id), paymentKey, amount, clock.instant())
		payment.markRequested(clock.instant(), reconcileInterval)
		payments.save(payment)
		return PreparedPayment.Started(snapshot(order, payment))
	}

	@Transactional
	fun apply(paymentId: Long, result: TossPaymentResult): PaymentResponse? {
		val orderId = payments.findOrderIdById(paymentId) ?: return null
		val saleId = orders.findSaleIdById(orderId) ?: return null
		sales.findByIdForUpdate(saleId) ?: return null
		val payment = payments.findById(paymentId) ?: return null
		val order = orders.findById(payment.orderId) ?: return null
		if (payment.status == PaymentStatus.APPROVED) return response(order, payment)
		if (payment.status != PaymentStatus.CONFIRMING) return null
		val now = clock.instant()
		when (result) {
			is TossPaymentResult.Record -> {
				if (result.paymentKey != payment.paymentKey || result.orderId != order.paymentOrderId || result.totalAmount != payment.requestedAmount) {
					payment.requireReview(now)
					log.warn("결제 수동 확인 필요: paymentId={}, reason=DATA_MISMATCH", paymentId)
				} else when (result.status) {
					TossPaymentStatuses.DONE -> if (result.approvedAt != null) {
						payment.approve(result.approvedAt)
						order.markPaid()
						return response(order, payment)
					} else {
						payment.requireReview(now)
						log.warn("결제 수동 확인 필요: paymentId={}, reason=APPROVED_AT_MISSING", paymentId)
					}
					TossPaymentStatuses.ABORTED, TossPaymentStatuses.EXPIRED -> payment.fail(now)
					TossPaymentStatuses.IN_PROGRESS -> payment.defer(now, reconcileInterval)
					else -> {
						payment.requireReview(now)
						log.warn("결제 수동 확인 필요: paymentId={}, reason=UNSUPPORTED_STATUS", paymentId)
					}
				}
			}
			TossPaymentResult.ConfirmedFailure -> payment.fail(now)
			TossPaymentResult.InvalidData -> {
				payment.requireReview(now)
				log.warn("결제 수동 확인 필요: paymentId={}, reason=INVALID_RESPONSE", paymentId)
			}
			TossPaymentResult.NotFound, TossPaymentResult.Unavailable -> payment.defer(now, reconcileInterval)
		}
		return null
	}

	@Transactional(readOnly = true)
	fun status(paymentId: Long): PaymentStatus? = payments.findById(paymentId)?.status

	@Transactional(readOnly = true)
	fun duePaymentIds(limit: Int): List<Long> = payments.findDue(clock.instant(), limit).mapNotNull { it.id }

	@Transactional
	fun claim(paymentId: Long): PaymentSnapshot? {
		val orderId = payments.findOrderIdById(paymentId) ?: return null
		val saleId = orders.findSaleIdById(orderId) ?: return null
		sales.findByIdForUpdate(saleId) ?: return null
		val payment = payments.findById(paymentId) ?: return null
		val order = orders.findById(payment.orderId) ?: return null
		val now = clock.instant()
		if (payment.status != PaymentStatus.CONFIRMING || payment.nextReconcileAt?.isAfter(now) != false) return null
		payment.defer(now, reconcileInterval)
		return snapshot(order, payment)
	}

	@Transactional
	fun reviewIfOverdue(paymentId: Long, maxAge: Duration) {
		val orderId = payments.findOrderIdById(paymentId) ?: return
		val saleId = orders.findSaleIdById(orderId) ?: return
		sales.findByIdForUpdate(saleId) ?: return
		val payment = payments.findById(paymentId) ?: return
		val firstRequestedAt = payment.firstRequestedAt ?: return
		if (payment.status == PaymentStatus.CONFIRMING && !clock.instant().isBefore(firstRequestedAt.plus(maxAge))) {
			payment.requireReview(clock.instant())
			log.warn("결제 수동 확인 필요: paymentId={}, reason=RECONCILE_TIMEOUT", paymentId)
		}
	}

	private fun ownedOrder(buyerId: Long, orderId: String): Order =
		orders.findByPaymentOrderId(orderId)?.takeIf { it.buyerId == buyerId }
			?: throw PaymentException(PaymentErrorCode.ORDER_NOT_FOUND)

	private fun snapshot(order: Order, payment: Payment): PaymentSnapshot = PaymentSnapshot(
		requireNotNull(payment.id), payment.paymentKey, order.paymentOrderId, payment.requestedAmount, payment.tossIdempotencyKey,
	)

	private fun response(order: Order, payment: Payment) = PaymentResponse(
		requireNotNull(order.id), order.paymentOrderId, OrderStatus.PAID.name, order.totalPrice, requireNotNull(payment.approvedAt),
	)

	private companion object {
		val log = LoggerFactory.getLogger(PaymentTransactionService::class.java)
	}
}

sealed interface PreparedPayment {
	data class Started(val payment: PaymentSnapshot) : PreparedPayment
	data class Completed(val response: PaymentResponse) : PreparedPayment
}

data class PaymentSnapshot(
	val id: Long,
	val paymentKey: String,
	val orderId: String,
	val amount: Long,
	val idempotencyKey: String,
)
