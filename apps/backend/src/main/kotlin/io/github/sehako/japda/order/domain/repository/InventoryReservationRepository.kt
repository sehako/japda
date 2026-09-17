package io.github.sehako.japda.order.domain.repository

import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import java.time.Instant
import java.util.UUID

interface InventoryReservationRepository {
	fun findByOrderId(orderId: Long): InventoryReservation?

	fun findExpiredReservedBySaleId(saleId: Long, now: Instant): List<InventoryReservation>

	fun save(reservation: InventoryReservation): InventoryReservation

	fun markPaymentPendingIfReservedAndNotExpired(orderId: Long, now: Instant): Boolean

	fun transitionById(
		id: UUID,
		expectedStatus: InventoryReservationStatus,
		targetStatus: InventoryReservationStatus,
		updatedAt: Instant,
	): Boolean

	fun transitionByOrderId(
		orderId: Long,
		expectedStatus: InventoryReservationStatus,
		targetStatus: InventoryReservationStatus,
		updatedAt: Instant,
	): Boolean
}
