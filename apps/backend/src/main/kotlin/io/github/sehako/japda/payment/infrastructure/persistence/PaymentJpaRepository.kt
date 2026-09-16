package io.github.sehako.japda.payment.infrastructure.persistence

import io.github.sehako.japda.payment.domain.model.Payment
import io.github.sehako.japda.payment.domain.model.PaymentStatus
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface PaymentJpaRepository : JpaRepository<Payment, Long> {
	fun findByOrderId(orderId: Long): Payment?

	@Query("select p.orderId from Payment p where p.id = :id")
	fun findOrderIdById(id: Long): Long?

	fun findByStatusAndNextReconcileAtLessThanEqualOrderByNextReconcileAtAscIdAsc(
		status: PaymentStatus,
		now: Instant,
		pageable: Pageable,
	): List<Payment>

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Payment payment
			set payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.APPROVED,
				payment.approvedAt = :approvedAt,
				payment.lastCheckedAt = :approvedAt,
				payment.nextReconcileAt = null
			where payment.id = :id
				and payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.CONFIRMING""",
	)
	fun approveIfConfirming(id: Long, approvedAt: Instant): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Payment payment
			set payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.FAILED,
				payment.lastCheckedAt = :checkedAt,
				payment.nextReconcileAt = null
			where payment.id = :id
				and payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.CONFIRMING""",
	)
	fun failIfConfirming(id: Long, checkedAt: Instant): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Payment payment
			set payment.lastCheckedAt = :now,
				payment.nextReconcileAt = :nextReconcileAt
			where payment.id = :id
				and payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.CONFIRMING
				and payment.nextReconcileAt <= :now""",
	)
	fun claimIfDue(id: Long, now: Instant, nextReconcileAt: Instant): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Payment payment
			set payment.lastCheckedAt = :checkedAt,
				payment.nextReconcileAt = :nextReconcileAt
			where payment.id = :id
				and payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.CONFIRMING""",
	)
	fun deferIfConfirming(id: Long, checkedAt: Instant, nextReconcileAt: Instant): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Payment payment
			set payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.REVIEW_REQUIRED,
				payment.lastCheckedAt = :checkedAt,
				payment.nextReconcileAt = null
			where payment.id = :id
				and payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.CONFIRMING""",
	)
	fun requireReviewIfConfirming(id: Long, checkedAt: Instant): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update Payment payment
			set payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.REVIEW_REQUIRED,
				payment.lastCheckedAt = :checkedAt,
				payment.nextReconcileAt = null
			where payment.id = :id
				and payment.status = io.github.sehako.japda.payment.domain.model.PaymentStatus.CONFIRMING
				and payment.firstRequestedAt <= :requestedAtOrBefore""",
	)
	fun requireReviewIfOverdue(id: Long, requestedAtOrBefore: Instant, checkedAt: Instant): Int
}
