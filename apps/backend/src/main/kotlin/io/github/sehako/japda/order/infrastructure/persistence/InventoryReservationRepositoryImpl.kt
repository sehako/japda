package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

@Repository
class InventoryReservationRepositoryImpl(
	private val jpaRepository: InventoryReservationJpaRepository,
) : InventoryReservationRepository {
	override fun findByOrderId(orderId: Long): InventoryReservation? = jpaRepository.findByOrderId(orderId)

	override fun findExpiredReservedBySaleId(saleId: Long, now: Instant): List<InventoryReservation> =
		jpaRepository.findAllBySaleIdAndStatusAndExpiresAtLessThanEqualOrderByIdAsc(
			saleId,
			InventoryReservationStatus.RESERVED,
			now,
		)

	override fun save(reservation: InventoryReservation): InventoryReservation = jpaRepository.saveAndFlush(reservation)

	override fun markPaymentPendingIfReservedAndNotExpired(orderId: Long, now: Instant): Boolean =
		jpaRepository.markPaymentPendingIfReservedAndNotExpired(
			orderId,
			InventoryReservationStatus.RESERVED,
			InventoryReservationStatus.PAYMENT_PENDING,
			now,
			now,
		) == 1

	override fun transitionById(
		id: UUID,
		expectedStatus: InventoryReservationStatus,
		targetStatus: InventoryReservationStatus,
		updatedAt: Instant,
	): Boolean = jpaRepository.transitionById(id, expectedStatus, targetStatus, updatedAt) == 1

	override fun transitionByOrderId(
		orderId: Long,
		expectedStatus: InventoryReservationStatus,
		targetStatus: InventoryReservationStatus,
		updatedAt: Instant,
	): Boolean = jpaRepository.transitionByOrderId(orderId, expectedStatus, targetStatus, updatedAt) == 1
}
