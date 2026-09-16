package io.github.sehako.japda.payment.domain.repository

import io.github.sehako.japda.payment.domain.model.Payment
import java.time.Instant

interface PaymentRepository {
	fun findByOrderId(orderId: Long): Payment?

	fun findById(id: Long): Payment?

	fun findOrderIdById(id: Long): Long?

	fun findDue(now: Instant, limit: Int): List<Payment>

	fun save(payment: Payment): Payment

	fun approveIfConfirming(id: Long, approvedAt: Instant): Boolean

	fun failIfConfirming(id: Long, checkedAt: Instant): Boolean

	fun claimIfDue(id: Long, now: Instant, nextReconcileAt: Instant): Boolean

	fun deferIfConfirming(id: Long, checkedAt: Instant, nextReconcileAt: Instant): Boolean

	fun requireReviewIfConfirming(id: Long, checkedAt: Instant): Boolean

	fun requireReviewIfOverdue(id: Long, requestedAtOrBefore: Instant, checkedAt: Instant): Boolean
}
