package io.github.sehako.japda.payment.infrastructure.persistence

import io.github.sehako.japda.payment.domain.model.Payment
import io.github.sehako.japda.payment.domain.model.PaymentStatus
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
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
}
