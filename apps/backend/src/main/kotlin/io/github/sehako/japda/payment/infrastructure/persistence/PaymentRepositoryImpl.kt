package io.github.sehako.japda.payment.infrastructure.persistence

import io.github.sehako.japda.payment.domain.model.Payment
import io.github.sehako.japda.payment.domain.model.PaymentStatus
import io.github.sehako.japda.payment.domain.repository.PaymentRepository
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class PaymentRepositoryImpl(private val jpaRepository: PaymentJpaRepository) : PaymentRepository {
	override fun findByOrderId(orderId: Long): Payment? = jpaRepository.findByOrderId(orderId)

	override fun findById(id: Long): Payment? = jpaRepository.findById(id).orElse(null)

	override fun findOrderIdById(id: Long): Long? = jpaRepository.findOrderIdById(id)

	override fun findDue(now: Instant, limit: Int): List<Payment> =
		jpaRepository.findByStatusAndNextReconcileAtLessThanEqualOrderByNextReconcileAtAscIdAsc(
			PaymentStatus.CONFIRMING, now, PageRequest.of(0, limit),
		)

	override fun save(payment: Payment): Payment = jpaRepository.saveAndFlush(payment)

	override fun approveIfConfirming(id: Long, approvedAt: Instant): Boolean =
		jpaRepository.approveIfConfirming(id, approvedAt) == 1

	override fun failIfConfirming(id: Long, checkedAt: Instant): Boolean =
		jpaRepository.failIfConfirming(id, checkedAt) == 1

	override fun claimIfDue(id: Long, now: Instant, nextReconcileAt: Instant): Boolean =
		jpaRepository.claimIfDue(id, now, nextReconcileAt) == 1

	override fun deferIfConfirming(id: Long, checkedAt: Instant, nextReconcileAt: Instant): Boolean =
		jpaRepository.deferIfConfirming(id, checkedAt, nextReconcileAt) == 1

	override fun requireReviewIfConfirming(id: Long, checkedAt: Instant): Boolean =
		jpaRepository.requireReviewIfConfirming(id, checkedAt) == 1

	override fun requireReviewIfOverdue(id: Long, requestedAtOrBefore: Instant, checkedAt: Instant): Boolean =
		jpaRepository.requireReviewIfOverdue(id, requestedAtOrBefore, checkedAt) == 1
}
