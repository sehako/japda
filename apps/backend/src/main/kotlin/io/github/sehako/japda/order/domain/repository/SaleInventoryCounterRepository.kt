package io.github.sehako.japda.order.domain.repository

import java.time.Instant

interface SaleInventoryCounterRepository {
	fun create(saleId: Long, now: Instant)

	fun reserve(saleId: Long, quantity: Int, now: Instant): SaleInventoryReserveResult

	fun release(saleId: Long, quantity: Int, now: Instant): Boolean

	fun findCommittedQuantity(saleId: Long): Int?
}

enum class SaleInventoryReserveResult {
	ACQUIRED,
	INSUFFICIENT,
	MISSING_COUNTER,
}
