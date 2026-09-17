package io.github.sehako.japda.payment.application.service

import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderStatus
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import io.github.sehako.japda.payment.application.client.TossPaymentStatuses
import io.github.sehako.japda.payment.application.response.PaymentResponse
import io.github.sehako.japda.payment.domain.model.Payment
import io.github.sehako.japda.payment.domain.model.PaymentStatus
import io.github.sehako.japda.payment.domain.repository.PaymentRepository
import io.github.sehako.japda.payment.exception.PaymentErrorCode
import io.github.sehako.japda.payment.exception.PaymentException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentTransactionService(
	private val orders: OrderRepository,
	private val payments: PaymentRepository,
	private val reservations: InventoryReservationRepository,
	private val inventoryCounters: SaleInventoryCounterRepository,
	private val clock: Clock,
	@Value("\${payment.reconcile-interval:PT30S}") private val reconcileInterval: Duration,
) {
	@Transactional
	fun prepare(buyerId: Long, orderId: String, paymentKey: String, amount: Long): PreparedPayment {
		val order = ownedOrder(buyerId, orderId)
		if (amount != order.totalPrice) throw PaymentException(PaymentErrorCode.AMOUNT_MISMATCH)
		val internalOrderId = requireNotNull(order.id)
		payments.findByOrderId(internalOrderId)?.let { return resolveExisting(order, it, paymentKey) }
		val now = clock.instant()
		if (order.status != OrderStatus.PENDING_PAYMENT) {
			throw PaymentException(PaymentErrorCode.ORDER_EXPIRED)
		}
		if (!reservations.markPaymentPendingIfReservedAndNotExpired(internalOrderId, now)) {
			payments.findByOrderId(internalOrderId)?.let { return resolveExisting(order, it, paymentKey) }
			throw PaymentException(PaymentErrorCode.ORDER_EXPIRED)
		}
		val payment = Payment.create(internalOrderId, paymentKey, amount, now)
		payment.markRequested(now, reconcileInterval)
		payments.save(payment)
		return PreparedPayment.Started(snapshot(order, payment))
	}

	@Transactional
	fun apply(paymentId: Long, result: TossPaymentResult): PaymentResponse? {
		val payment = payments.findById(paymentId) ?: return null
		val order = orders.findById(payment.orderId) ?: return null
		if (payment.status == PaymentStatus.APPROVED) return response(order, payment)
		if (payment.status != PaymentStatus.CONFIRMING) return null
		val now = clock.instant()
		when (result) {
			is TossPaymentResult.Record -> {
				if (result.paymentKey != payment.paymentKey || result.orderId != order.paymentOrderId || result.totalAmount != payment.requestedAmount) {
					requireReview(paymentId, now, "DATA_MISMATCH")
				} else when (result.status) {
					TossPaymentStatuses.DONE -> if (result.approvedAt != null) {
						return approve(payment, order, result.approvedAt, now)
					} else {
						requireReview(paymentId, now, "APPROVED_AT_MISSING")
					}
					TossPaymentStatuses.ABORTED, TossPaymentStatuses.EXPIRED -> fail(payment, now)
					TossPaymentStatuses.IN_PROGRESS -> defer(paymentId, now)
					else -> {
						requireReview(paymentId, now, "UNSUPPORTED_STATUS")
					}
				}
			}
			TossPaymentResult.ConfirmedFailure -> fail(payment, now)
			TossPaymentResult.InvalidData -> {
				requireReview(paymentId, now, "INVALID_RESPONSE")
			}
			TossPaymentResult.NotFound, TossPaymentResult.Unavailable -> defer(paymentId, now)
		}
		return null
	}

	@Transactional(readOnly = true)
	fun status(paymentId: Long): PaymentStatus? = payments.findById(paymentId)?.status

	@Transactional(readOnly = true)
	fun duePaymentIds(limit: Int): List<Long> = payments.findDue(clock.instant(), limit).mapNotNull { it.id }

	@Transactional
	fun claim(paymentId: Long): PaymentSnapshot? {
		val now = clock.instant()
		if (!payments.claimIfDue(paymentId, now, now.plus(reconcileInterval))) return null
		val payment = payments.findById(paymentId) ?: return null
		val order = orders.findById(payment.orderId) ?: return null
		return snapshot(order, payment)
	}

	@Transactional
	fun reviewIfOverdue(paymentId: Long, maxAge: Duration) {
		val now = clock.instant()
		if (payments.requireReviewIfOverdue(paymentId, now.minus(maxAge), now)) {
			log.warn("결제 수동 확인 필요: paymentId={}, reason=RECONCILE_TIMEOUT", paymentId)
		}
	}

	private fun resolveExisting(order: Order, payment: Payment, paymentKey: String): PreparedPayment {
		if (payment.paymentKey != paymentKey) throw PaymentException(PaymentErrorCode.KEY_CONFLICT)
		return when (payment.status) {
			PaymentStatus.APPROVED -> PreparedPayment.Completed(response(order, payment))
			PaymentStatus.CONFIRMING -> throw PaymentException(PaymentErrorCode.IN_PROGRESS)
			PaymentStatus.FAILED -> throw PaymentException(PaymentErrorCode.FAILED)
			PaymentStatus.REVIEW_REQUIRED -> throw PaymentException(PaymentErrorCode.REVIEW_REQUIRED)
		}
	}

	private fun approve(payment: Payment, order: Order, approvedAt: Instant, now: Instant): PaymentResponse? {
		if (!payments.approveIfConfirming(requireNotNull(payment.id), approvedAt)) {
			val current = payments.findById(requireNotNull(payment.id)) ?: return null
			return if (current.status == PaymentStatus.APPROVED) response(order, current) else null
		}
		check(orders.markPaidIfPending(payment.orderId)) { "결제 승인 주문 상태 전이에 실패했습니다." }
		check(
			reservations.transitionByOrderId(
				payment.orderId,
				InventoryReservationStatus.PAYMENT_PENDING,
				InventoryReservationStatus.CONFIRMED,
				now,
			),
		) { "결제 승인 예약 상태 전이에 실패했습니다." }
		return PaymentResponse(requireNotNull(order.id), order.paymentOrderId, OrderStatus.PAID.name, order.totalPrice, approvedAt)
	}

	private fun fail(payment: Payment, now: Instant) {
		if (!payments.failIfConfirming(requireNotNull(payment.id), now)) return
		val reservation = checkNotNull(reservations.findByOrderId(payment.orderId)) {
			"결제 실패 주문의 재고 예약을 찾을 수 없습니다."
		}
		check(
			reservations.transitionByOrderId(
				payment.orderId,
				InventoryReservationStatus.PAYMENT_PENDING,
				InventoryReservationStatus.RELEASED,
				now,
			),
		) { "결제 실패 예약 상태 전이에 실패했습니다." }
		if (!inventoryCounters.release(reservation.saleId, reservation.quantity, now)) {
			log.error(
				"결제 실패 예약 반환 중 재고 카운터 감소에 실패했습니다. saleId={}, orderId={}, reservationId={}",
				reservation.saleId,
				reservation.orderId,
				reservation.id,
			)
			error("결제 실패 예약 반환 중 재고 카운터 감소에 실패했습니다.")
		}
	}

	private fun defer(paymentId: Long, now: Instant) {
		payments.deferIfConfirming(paymentId, now, now.plus(reconcileInterval))
	}

	private fun requireReview(paymentId: Long, now: Instant, reason: String) {
		if (payments.requireReviewIfConfirming(paymentId, now)) {
			log.warn("결제 수동 확인 필요: paymentId={}, reason={}", paymentId, reason)
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
