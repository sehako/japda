package io.github.sehako.japda.payment.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payments")
class Payment private constructor(
	id: Long?,
	@field:Column(name = "order_id", nullable = false, unique = true)
	val orderId: Long,
	@field:Column(name = "payment_key", nullable = false, unique = true, length = 200)
	val paymentKey: String,
	@field:Column(name = "toss_idempotency_key", nullable = false, unique = true, length = 36)
	val tossIdempotencyKey: String,
	status: PaymentStatus,
	@field:Column(name = "requested_amount", nullable = false)
	val requestedAmount: Long,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
	firstRequestedAt: Instant?,
	lastCheckedAt: Instant?,
	nextReconcileAt: Instant?,
	approvedAt: Instant?,
) {
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = id
		protected set

	@field:Enumerated(EnumType.STRING)
	@field:Column(nullable = false, length = 30)
	var status: PaymentStatus = status
		protected set

	@field:Column(name = "first_requested_at")
	var firstRequestedAt: Instant? = firstRequestedAt
		protected set

	@field:Column(name = "last_checked_at")
	var lastCheckedAt: Instant? = lastCheckedAt
		protected set

	@field:Column(name = "next_reconcile_at")
	var nextReconcileAt: Instant? = nextReconcileAt
		protected set

	@field:Column(name = "approved_at")
	var approvedAt: Instant? = approvedAt
		protected set

	fun requireSameKey(key: String) {
		require(paymentKey == key) { "다른 결제 키로 시도를 재사용할 수 없습니다." }
	}

	fun markRequested(now: Instant, interval: Duration) {
		check(status == PaymentStatus.CONFIRMING)
		if (firstRequestedAt == null) firstRequestedAt = now
		lastCheckedAt = now
		nextReconcileAt = now.plus(interval)
	}

	fun defer(now: Instant, interval: Duration) {
		check(status == PaymentStatus.CONFIRMING)
		lastCheckedAt = now
		nextReconcileAt = now.plus(interval)
	}

	fun approve(at: Instant) {
		check(status == PaymentStatus.CONFIRMING)
		status = PaymentStatus.APPROVED
		approvedAt = at
		lastCheckedAt = at
		nextReconcileAt = null
	}

	fun fail(at: Instant) {
		check(status == PaymentStatus.CONFIRMING)
		status = PaymentStatus.FAILED
		lastCheckedAt = at
		nextReconcileAt = null
	}

	fun requireReview(at: Instant) {
		check(status == PaymentStatus.CONFIRMING)
		status = PaymentStatus.REVIEW_REQUIRED
		lastCheckedAt = at
		nextReconcileAt = null
	}

	companion object {
		private val DEFAULT_RECONCILE_INTERVAL = Duration.ofSeconds(30)

		fun create(orderId: Long, paymentKey: String, amount: Long, now: Instant): Payment {
			require(orderId > 0 && paymentKey.isNotBlank() && amount > 0)
			return Payment(
				id = null,
				orderId = orderId,
				paymentKey = paymentKey,
				tossIdempotencyKey = UUID.randomUUID().toString(),
				status = PaymentStatus.CONFIRMING,
				requestedAmount = amount,
				createdAt = now,
				firstRequestedAt = null,
				lastCheckedAt = null,
				nextReconcileAt = now.plus(DEFAULT_RECONCILE_INTERVAL),
				approvedAt = null,
			)
		}
	}
}
