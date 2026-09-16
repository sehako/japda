package io.github.sehako.japda.order.application.inventory

interface InventoryReservation {
	fun reserve(saleId: Long, quantity: Int): InventoryReservationResult

	fun restore(token: InventoryReservationToken)

	fun invalidate(token: InventoryReservationToken)
}
