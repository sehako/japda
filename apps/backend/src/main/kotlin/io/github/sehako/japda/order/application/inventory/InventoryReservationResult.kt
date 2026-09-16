package io.github.sehako.japda.order.application.inventory

sealed interface InventoryReservationResult {
	data class Reserved(
		val token: InventoryReservationToken,
	) : InventoryReservationResult

	data object Insufficient : InventoryReservationResult

	data object Fallback : InventoryReservationResult
}

data class InventoryReservationToken(
	val reservationId: String,
	val saleId: Long,
	val generation: String,
	val quantity: Int,
)
