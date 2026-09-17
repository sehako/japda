package io.github.sehako.japda.order.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inventory_reservations")
class InventoryReservation private constructor(
	@field:Id
	val id: UUID,
	@field:Column(name = "sale_id", nullable = false)
	val saleId: Long,
	@field:Column(name = "order_id", nullable = false, unique = true)
	val orderId: Long,
	@field:Column(nullable = false)
	val quantity: Int,
	status: InventoryReservationStatus,
	@field:Column(name = "expires_at", nullable = false)
	val expiresAt: Instant,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
	updatedAt: Instant,
) {
	@field:Enumerated(EnumType.STRING)
	@field:Column(nullable = false, length = 30)
	var status: InventoryReservationStatus = status
		protected set

	@field:Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = updatedAt
		protected set

	companion object {
		fun reserve(
			id: UUID,
			saleId: Long,
			orderId: Long,
			quantity: Int,
			expiresAt: Instant,
			createdAt: Instant,
		): InventoryReservation {
			require(saleId > 0) { "판매 일정 식별자는 양수여야 합니다." }
			require(orderId > 0) { "주문 식별자는 양수여야 합니다." }
			require(quantity > 0) { "예약 수량은 양수여야 합니다." }
			return InventoryReservation(
				id = id,
				saleId = saleId,
				orderId = orderId,
				quantity = quantity,
				status = InventoryReservationStatus.RESERVED,
				expiresAt = expiresAt,
				createdAt = createdAt,
				updatedAt = createdAt,
			)
		}
	}
}
