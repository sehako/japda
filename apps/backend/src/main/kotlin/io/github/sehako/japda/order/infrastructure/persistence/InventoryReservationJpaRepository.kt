package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.InventoryReservation
import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InventoryReservationJpaRepository : JpaRepository<InventoryReservation, UUID> {
	fun findByOrderId(orderId: Long): InventoryReservation?

	fun findAllBySaleIdAndStatusAndExpiresAtLessThanEqualOrderByIdAsc(
		saleId: Long,
		status: InventoryReservationStatus,
		expiresAt: Instant,
	): List<InventoryReservation>

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update InventoryReservation reservation
			set reservation.status = :targetStatus, reservation.updatedAt = :updatedAt
			where reservation.orderId = :orderId
			and reservation.status = :expectedStatus
			and reservation.expiresAt > :now""",
	)
	fun markPaymentPendingIfReservedAndNotExpired(
		@Param("orderId") orderId: Long,
		@Param("expectedStatus") expectedStatus: InventoryReservationStatus,
		@Param("targetStatus") targetStatus: InventoryReservationStatus,
		@Param("now") now: Instant,
		@Param("updatedAt") updatedAt: Instant,
	): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update InventoryReservation reservation
			set reservation.status = :targetStatus, reservation.updatedAt = :updatedAt
			where reservation.id = :id and reservation.status = :expectedStatus""",
	)
	fun transitionById(
		@Param("id") id: UUID,
		@Param("expectedStatus") expectedStatus: InventoryReservationStatus,
		@Param("targetStatus") targetStatus: InventoryReservationStatus,
		@Param("updatedAt") updatedAt: Instant,
	): Int

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(
		"""update InventoryReservation reservation
			set reservation.status = :targetStatus, reservation.updatedAt = :updatedAt
			where reservation.orderId = :orderId and reservation.status = :expectedStatus""",
	)
	fun transitionByOrderId(
		@Param("orderId") orderId: Long,
		@Param("expectedStatus") expectedStatus: InventoryReservationStatus,
		@Param("targetStatus") targetStatus: InventoryReservationStatus,
		@Param("updatedAt") updatedAt: Instant,
	): Int
}
