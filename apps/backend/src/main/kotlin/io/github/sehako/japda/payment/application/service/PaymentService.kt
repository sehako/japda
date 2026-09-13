package io.github.sehako.japda.payment.application.service

import io.github.sehako.japda.payment.application.client.TossPaymentClient
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import io.github.sehako.japda.payment.application.client.TossPaymentStatuses
import io.github.sehako.japda.payment.application.dto.ConfirmPaymentDto
import io.github.sehako.japda.payment.application.response.PaymentResponse
import io.github.sehako.japda.payment.domain.model.PaymentStatus
import io.github.sehako.japda.payment.exception.PaymentErrorCode
import io.github.sehako.japda.payment.exception.PaymentException
import java.time.Duration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class PaymentService(
	private val transactions: PaymentTransactionService,
	private val toss: TossPaymentClient,
) {
	fun confirm(dto: ConfirmPaymentDto): PaymentResponse {
		val paymentKey = dto.paymentKey?.takeIf { it.isNotBlank() && it.length <= MAX_PAYMENT_KEY_LENGTH }
			?: throw PaymentException(PaymentErrorCode.KEY_INVALID)
		val orderId = dto.orderId?.takeIf { ORDER_ID_PATTERN.matches(it) }
			?: throw PaymentException(PaymentErrorCode.ORDER_ID_INVALID)
		val amount = dto.amount?.takeIf { it > 0 } ?: throw PaymentException(PaymentErrorCode.AMOUNT_INVALID)
		return when (val prepared = transactions.prepare(dto.buyerId, orderId, paymentKey, amount)) {
			is PreparedPayment.Completed -> prepared.response
			is PreparedPayment.Started -> {
				val payment = prepared.payment
				val result = toss.confirm(payment.paymentKey, payment.orderId, payment.amount, payment.idempotencyKey)
				transactions.apply(payment.id, result)?.let { return it }
				throw when (transactions.status(payment.id)) {
					PaymentStatus.FAILED -> PaymentException(PaymentErrorCode.FAILED)
					PaymentStatus.REVIEW_REQUIRED -> PaymentException(PaymentErrorCode.REVIEW_REQUIRED)
					else -> PaymentException(PaymentErrorCode.UNAVAILABLE)
				}
			}
		}
	}

	@Scheduled(fixedDelayString = "\${payment.reconcile-interval:PT30S}")
	fun reconcileDue() {
		transactions.duePaymentIds(RECONCILE_BATCH_SIZE).forEach { id ->
			try {
				val payment = transactions.claim(id) ?: return@forEach
				val lookup = toss.lookup(payment.paymentKey)
				val result = when (lookup) {
					TossPaymentResult.NotFound -> toss.confirm(payment.paymentKey, payment.orderId, payment.amount, payment.idempotencyKey)
					is TossPaymentResult.Record -> if (
						lookup.paymentKey == payment.paymentKey && lookup.orderId == payment.orderId &&
						lookup.totalAmount == payment.amount && lookup.status == TossPaymentStatuses.IN_PROGRESS
					) {
						toss.confirm(payment.paymentKey, payment.orderId, payment.amount, payment.idempotencyKey)
					} else lookup
					else -> lookup
				}
				transactions.apply(id, result)
			} catch (_: Exception) {
				// 다음 주기에서 저장된 결제 시도를 다시 확인한다.
			} finally {
				try {
					transactions.reviewIfOverdue(id, MAX_RECONCILE_AGE)
				} catch (_: Exception) {
					// DB 장애 중에는 다음 주기에서 다시 확인한다.
				}
			}
		}
	}

	private companion object {
		const val MAX_PAYMENT_KEY_LENGTH = 200
		const val RECONCILE_BATCH_SIZE = 100
		val ORDER_ID_PATTERN = Regex("^[A-Za-z0-9_=-]{6,64}$")
		val MAX_RECONCILE_AGE: Duration = Duration.ofMinutes(15)
	}
}
