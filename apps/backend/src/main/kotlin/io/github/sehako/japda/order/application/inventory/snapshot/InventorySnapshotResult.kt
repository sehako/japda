package io.github.sehako.japda.order.application.inventory.snapshot

sealed interface InventorySnapshotResult {
	data class Available(val quantity: Int) : InventorySnapshotResult

	data object InitializationFailed : InventorySnapshotResult
}
