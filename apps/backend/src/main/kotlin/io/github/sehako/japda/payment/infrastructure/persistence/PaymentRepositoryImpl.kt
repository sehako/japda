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
}
