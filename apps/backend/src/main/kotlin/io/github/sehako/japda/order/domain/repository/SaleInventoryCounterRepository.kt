package io.github.sehako.japda.order.domain.repository

import java.time.Instant

interface SaleInventoryCounterRepository {
	fun create(saleId: Long, now: Instant)

	fun reserve(saleId: Long, quantity: Int, now: Instant): SaleInventoryReserveResult

	fun release(saleId: Long, quantity: Int, now: Instant): Boolean

	fun findCommittedQuantity(saleId: Long): Int?
}

sealed interface SaleInventoryReserveResult {
	data object Acquired : SaleInventoryReserveResult

	data class Insufficient(val remainingQuantity: Int) : SaleInventoryReserveResult

	data object MissingCounter : SaleInventoryReserveResult
}
