package io.github.sehako.japda.payment.domain.repository

import io.github.sehako.japda.payment.domain.model.Payment
import java.time.Instant

interface PaymentRepository {
	fun findByOrderId(orderId: Long): Payment?

	fun findById(id: Long): Payment?

	fun findOrderIdById(id: Long): Long?

	fun findDue(now: Instant, limit: Int): List<Payment>

	fun save(payment: Payment): Payment
}
